package com.younglings.bot.kick;

import com.younglings.bot.discord.Mentions;
import com.younglings.bot.alerts.AutoPostRouter;
import com.younglings.bot.kick.KickClient.Stream;
import com.younglings.bot.kick.KickRepository.Settings;
import com.younglings.bot.kick.KickRepository.Streamer;
import com.younglings.bot.kick.KickRules.OfflineAction;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Every two minutes, asks Kick which of the followed streamers are live and keeps each server's go-live post in step: one post per stream (claimed
 * in the database before it is sent, so it can never double), and when the stream has been over for two polls the post is edited, deleted or left,
 * as the server chose. Nothing runs without the Kick app's id and secret, and nothing runs for a server until it switches Kick alerts on. A
 * streamer already live when first looked at is recorded without a post. Kick being down just skips a round.
 */
@BService
public class KickService {
    private static final Logger log = LoggerFactory.getLogger(KickService.class);
    static final int POLL_MINUTES = 2;
    static final Permission[] NEEDED = {Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS};

    private final KickRepository repository;
    private final KickClient client;
    private final ScheduledExecutorService executor = BotScheduler.lane("kick");

    public KickService(KickRepository repository, KickClient client) {
        this.repository = repository;
        this.client = client;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!client.configured()) {
            log.info("Kick alerts are off: {} and {} are not set.", KickClient.ID_VARIABLE, KickClient.SECRET_VARIABLE);
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
            if (streamers.isEmpty()) return;
            List<String> logins = streamers.stream().map(Streamer::login).distinct().toList();
            Map<String, Stream> live;
            try {
                live = client.live(logins);
            } catch (IOException e) {
                log.warn("Kick could not be asked this round: {}", e.getMessage());
                return;
            }
            for (Streamer streamer : streamers) {
                try {
                    handle(jda, streamer, live.get(streamer.login()));
                } catch (RuntimeException e) {
                    log.warn("Kick alert for streamer {} in guild {} failed", streamer.id(), streamer.guildId(), e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Kick poll failed", e);
        }
    }

    void handle(JDA jda, Streamer streamer, Stream stream) {
        String liveId = stream == null ? null : stream.id();
        switch (KickRules.decide(streamer.primed(), streamer.postedStream(), liveId, streamer.misses())) {
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
        Guild guild = jda.getGuildById(streamer.guildId());
        GuildMessageChannel channel = guild == null ? null : guild.getChannelById(GuildMessageChannel.class, streamer.channelId());
        if (channel == null || problemWith(channel) != null) {
            log.warn("Kick alert for {} in guild {}: the channel is gone or JonnyBot cannot post there", streamer.login(), streamer.guildId());
            return;
        }
        String url = "https://kick.com/" + stream.login();
        String line = KickRules.render(streamer.message(), stream.name(), stream.title(), stream.game(), url);
        EmbedBuilder embed = new EmbedBuilder().setColor(new Color(0x53FC18)).setTitle(trim(stream.title().isBlank() ? stream.name() + " is live" : stream.title(), 256), url)
                .setAuthor(stream.name(), url);
        if (!stream.game().isBlank()) embed.addField("Playing", trim(stream.game(), 256), true);
        embed.addField("Watching", Integer.toString(stream.viewers()), true);
        String picture = KickRules.thumbnail(stream.thumbnailTemplate(), System.currentTimeMillis());
        if (picture != null) embed.setImage(picture);

        MessageCreateBuilder message = new MessageCreateBuilder().setContent(trim((streamer.pingRoleId() == null ? "" : "<@&" + streamer.pingRoleId() + "> ") + line, 2000)).setEmbeds(embed.build());
        // only the alert's own role pings: the stream title (the streamer's text) never does
        if (streamer.pingRoleId() != null) Mentions.onlyRoles(message, streamer.pingRoleId());
        else message.setAllowedMentions(Mentions.NONE);
        channel.sendMessage(message.build()).queue(sent -> repository.recordMessage(streamer.id(), channel.getIdLong(), sent.getIdLong()),
                t -> log.warn("Kick post failed in guild {}: {}", streamer.guildId(), t.getMessage()));
    }

    private void end(JDA jda, Streamer streamer) {
        Settings settings = repository.settings(streamer.guildId());
        Guild guild = jda.getGuildById(streamer.guildId());
        if (guild != null && streamer.postedMessageId() != null && streamer.postedChannelId() != null && settings.offlineAction() != OfflineAction.KEEP) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, streamer.postedChannelId());
            if (channel != null) {
                if (settings.offlineAction() == OfflineAction.DELETE) {
                    channel.deleteMessageById(streamer.postedMessageId()).queue(null, t -> log.debug("Kick post already gone: {}", t.getMessage()));
                } else {
                    String name = streamer.displayName().isBlank() ? streamer.login() : streamer.displayName();
                    channel.editMessageById(streamer.postedMessageId(), new MessageEditBuilder().setContent(name + " was live on Kick. The stream has ended.").setEmbeds(List.of())
                            .setAllowedMentions(java.util.EnumSet.noneOf(Message.MentionType.class)).build()).queue(null, t -> log.debug("Kick post could not be edited: {}", t.getMessage()));
                }
            }
        }
        repository.clearPosted(streamer.id());
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
