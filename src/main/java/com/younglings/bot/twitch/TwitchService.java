package com.younglings.bot.twitch;

import com.younglings.bot.discord.Shards;
import com.younglings.bot.discord.Mentions;
import com.younglings.bot.alerts.AutoPostRouter;
import com.younglings.bot.twitch.TwitchClient.Stream;
import com.younglings.bot.twitch.TwitchRepository.Settings;
import com.younglings.bot.twitch.TwitchRepository.Streamer;
import com.younglings.bot.twitch.TwitchRules.OfflineAction;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Every two minutes, asks Twitch which of the followed streamers are live and keeps each server's go-live post in step: one post per stream (claimed
 * in the database before it is sent, so it can never double), and when the stream has been over for two polls the post is edited, deleted or left,
 * as the server chose. Nothing runs without the Twitch app's id and secret, and nothing runs for a server until it switches Twitch alerts on. A
 * streamer already live when first looked at is recorded without a post. Twitch being down just skips a round.
 */
@BService
public class TwitchService {
    private static final Logger log = LoggerFactory.getLogger(TwitchService.class);
    static final int POLL_MINUTES = 2;
    static final Permission[] NEEDED = {Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS};

    private final TwitchRepository repository;
    private final TwitchClient client;
    private final ScheduledExecutorService executor = BotScheduler.lane("twitch");
    /** Who Twitch said was live at the last poll, by login, and when (T-587: the clan website's "Live now" widget reads it, never Twitch itself). */
    private volatile Map<String, Stream> lastLive = Map.of();
    private volatile Instant lastPoll = Instant.EPOCH;
    /** Older than this, the last poll's answer is not shown as "live now" (Twitch has been unreachable for a while). */
    static final Duration STALE = Duration.ofMinutes(10);

    public TwitchService(TwitchRepository repository, TwitchClient client) {
        this.repository = repository;
        this.client = client;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!client.configured()) {
            log.info("Twitch alerts are off: {} and {} are not set.", TwitchClient.ID_VARIABLE, TwitchClient.SECRET_VARIABLE);
            return;
        }
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> poll(jda), 1, POLL_MINUTES, TimeUnit.MINUTES);
    }

    /** Whether JonnyBot can post in this channel, as words for the person setting it up (null when it can). */
    public static String problemWith(GuildMessageChannel channel) {
        if (!channel.getGuild().getSelfMember().hasPermission(channel, NEEDED)) {
            return "The bot needs View Channel, Send Messages and Embed Links in #" + channel.getName() + " to post there.";
        }
        return null;
    }

    void poll(JDA jda) {
        if (AutoPostRouter.diverting()) return; // go-live posts are tracked in the database and edited later: a bot that is not live makes none
        try {
            List<Streamer> streamers = repository.active();
            if (streamers.isEmpty()) {
                remember(Map.of(), Instant.now());
                return;
            }
            List<String> logins = streamers.stream().map(Streamer::login).distinct().toList();
            Map<String, Stream> live;
            try {
                live = client.live(logins);
            } catch (IOException e) {
                log.warn("Twitch could not be asked this round: {}", e.getMessage());
                return;
            }
            remember(live, Instant.now());
            for (Streamer streamer : streamers) {
                try {
                    handle(jda, streamer, live.get(streamer.login()));
                } catch (RuntimeException e) {
                    log.warn("Twitch alert for streamer {} in guild {} failed", streamer.id(), streamer.guildId(), e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Twitch poll failed", e);
        }
    }

    /** Keeps a poll's answer for {@link #liveFor}. */
    void remember(Map<String, Stream> live, Instant at) {
        Map<String, Stream> copy = new HashMap<>();
        live.forEach((login, stream) -> {
            if (login != null && stream != null) copy.put(login, stream);
        });
        lastLive = Map.copyOf(copy);
        lastPoll = at;
    }

    /** Whether the server follows streamers with the bot (the Twitch app is set up and the server's Twitch alerts are on). */
    public boolean followsStreamers(long guildId) {
        return client.configured() && repository.settings(guildId).enabled();
    }

    /**
     * The server's followed streamers who were live at the last poll (at most two minutes old), most viewers first: what the clan website's
     * "Live now" widget shows (T-587). Empty while the server's Twitch alerts are off, or when the last answer is over {@link #STALE} old.
     */
    public List<Stream> liveFor(long guildId, Instant now) {
        if (!followsStreamers(guildId) || lastPoll.plus(STALE).isBefore(now)) return List.of();
        Map<String, Stream> live = lastLive;
        return repository.streamers(guildId).stream().filter(Streamer::enabled).map(s -> live.get(s.login())).filter(Objects::nonNull).distinct()
                .sorted(Comparator.comparingInt(Stream::viewers).reversed().thenComparing(Stream::login)).toList();
    }

    void handle(JDA jda, Streamer streamer, Stream stream) {
        String liveId = stream == null ? null : stream.id();
        switch (TwitchRules.decide(streamer.primed(), streamer.postedStream(), liveId, streamer.misses())) {
            case NOTHING -> {}
            case PRIME -> repository.prime(streamer.id(), liveId);
            case RESET_MISSES -> repository.setMisses(streamer.id(), 0);
            case COUNT_MISS -> repository.setMisses(streamer.id(), streamer.misses() + 1);
            case ANNOUNCE -> announce(jda, streamer, stream);
            case END -> end(jda, streamer);
        }
    }

    private void announce(JDA jda, Streamer streamer, Stream stream) {
        // claim first: if two rounds overlap, only one gets to send
        if (!repository.claim(streamer.id(), stream.id(), streamer.postedStream())) return;
        Guild guild = Shards.guild(jda, streamer.guildId());
        GuildMessageChannel channel = guild == null ? null : guild.getChannelById(GuildMessageChannel.class, streamer.channelId());
        if (channel == null || problemWith(channel) != null) {
            log.warn("Twitch alert for {} in guild {}: the channel is gone or JonnyBot cannot post there", streamer.login(), streamer.guildId());
            return;
        }
        String url = "https://www.twitch.tv/" + stream.login();
        String line = TwitchRules.render(streamer.message(), stream.name(), stream.title(), stream.game(), url);
        EmbedBuilder embed = new EmbedBuilder().setColor(new Color(0x9146FF)).setTitle(trim(stream.title().isBlank() ? stream.name() + " is live" : stream.title(), 256), url)
                .setAuthor(stream.name(), url);
        if (!stream.game().isBlank()) embed.addField("Playing", trim(stream.game(), 256), true);
        embed.addField("Watching", Integer.toString(stream.viewers()), true);
        String picture = TwitchRules.thumbnail(stream.thumbnailTemplate(), 440, 248, System.currentTimeMillis());
        if (picture != null) embed.setImage(picture);

        MessageCreateBuilder message = new MessageCreateBuilder().setContent(trim((streamer.pingRoleId() == null ? "" : "<@&" + streamer.pingRoleId() + "> ") + line, 2000)).setEmbeds(embed.build());
        // only the alert's own role pings: the stream title (the streamer's text) never does
        if (streamer.pingRoleId() != null) Mentions.onlyRoles(message, streamer.pingRoleId());
        else message.setAllowedMentions(Mentions.NONE);
        channel.sendMessage(message.build()).queue(sent -> repository.recordMessage(streamer.id(), channel.getIdLong(), sent.getIdLong()),
                t -> log.warn("Twitch post failed in guild {}: {}", streamer.guildId(), t.getMessage()));
    }

    private void end(JDA jda, Streamer streamer) {
        Settings settings = repository.settings(streamer.guildId());
        Guild guild = Shards.guild(jda, streamer.guildId());
        if (guild != null && streamer.postedMessageId() != null && streamer.postedChannelId() != null && settings.offlineAction() != OfflineAction.KEEP) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, streamer.postedChannelId());
            if (channel != null) {
                if (settings.offlineAction() == OfflineAction.DELETE) {
                    channel.deleteMessageById(streamer.postedMessageId()).queue(null, t -> log.debug("Twitch post already gone: {}", t.getMessage()));
                } else {
                    String name = streamer.displayName().isBlank() ? streamer.login() : streamer.displayName();
                    channel.editMessageById(streamer.postedMessageId(), new MessageEditBuilder().setContent(name + " was live on Twitch. The stream has ended.").setEmbeds(List.of())
                            .setAllowedMentions(java.util.EnumSet.noneOf(Message.MentionType.class)).build()).queue(null, t -> log.debug("Twitch post could not be edited: {}", t.getMessage()));
                }
            }
        }
        repository.clearPosted(streamer.id());
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
