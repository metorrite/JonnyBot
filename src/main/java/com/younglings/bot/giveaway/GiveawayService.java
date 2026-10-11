package com.younglings.bot.giveaway;

import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Posting giveaways, drawing winners when they end (on time, or when told to), and rerolling. */
@BService
public class GiveawayService {
    private static final Logger log = LoggerFactory.getLogger(GiveawayService.class);

    /** Why something couldn't be done, in words for the person who asked. */
    public static final class GiveawayException extends RuntimeException {
        public GiveawayException(String message) {
            super(message);
        }
    }

    private final GiveawayRepository repository;
    private final Random random = new SecureRandom();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor((ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "giveaways");
        thread.setDaemon(true);
        return thread;
    });

    public GiveawayService(GiveawayRepository repository) {
        this.repository = repository;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> sweep(jda), 30, 15, TimeUnit.SECONDS);
    }

    /** Creates the giveaway and posts its message. Throws {@link GiveawayException} if the channel can't be used (nothing is kept then). */
    public Giveaway start(Guild guild, long channelId, String prize, long hostId, int winners, Instant endsAt, Long requiredRoleId) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new GiveawayException("That channel doesn't exist in this server.");
        if (!channel.canTalk()) throw new GiveawayException("JonnyBot can't post in #" + channel.getName() + ". It needs permission to view the channel and send messages there.");

        long id = repository.create(guild.getIdLong(), channelId, prize, hostId, winners, endsAt, requiredRoleId);
        Giveaway g = repository.get(guild.getIdLong(), id).orElseThrow();
        try {
            var edit = GiveawayMessage.edit(g, 0);
            Message sent = channel.sendMessage(new MessageCreateBuilder().setEmbeds(edit.getEmbeds()).setComponents(edit.getComponents()).build()).complete();
            repository.setMessage(id, sent.getIdLong());
            return repository.get(guild.getIdLong(), id).orElseThrow();
        } catch (RuntimeException e) {
            repository.delete(guild.getIdLong(), id);
            log.warn("Couldn't post giveaway {} in channel {}", id, channelId, e);
            throw new GiveawayException("Discord refused the message. Check JonnyBot can post and embed links in #" + channel.getName() + ".");
        }
    }

    /** Refreshes the entry count shown on the button. */
    public void refresh(Guild guild, Giveaway g) {
        if (g.messageId() == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, g.channelId());
        if (channel == null) return;
        channel.editMessageById(g.messageId(), GiveawayMessage.edit(g, repository.entryCount(g.id()))).queue(ok -> {}, error -> {});
    }

    /** Ends it now (or when due): draws, shows the result on its message, and announces it. */
    public Giveaway end(Guild guild, Giveaway g) {
        if (!g.open()) throw new GiveawayException("That giveaway has already ended.");
        List<Long> winners = draw(guild, g, Set.of());
        repository.finish(g.id(), winners);
        Giveaway ended = repository.get(guild.getIdLong(), g.id()).orElseThrow();
        show(guild, ended, winners, false);
        return ended;
    }

    /** Draws new winners for an ended giveaway, never the same people again. */
    public Giveaway reroll(Guild guild, Giveaway g) {
        if (g.open()) throw new GiveawayException("That giveaway is still running. End it first.");
        List<Long> winners = draw(guild, g, new HashSet<>(g.winners()));
        if (winners.isEmpty()) throw new GiveawayException("There is nobody left to draw: everyone else who entered has left, or doesn't qualify.");
        repository.setWinners(g.id(), winners);
        Giveaway updated = repository.get(guild.getIdLong(), g.id()).orElseThrow();
        show(guild, updated, winners, true);
        return updated;
    }

    private List<Long> draw(Guild guild, Giveaway g, Set<Long> exclude) {
        List<Long> entrants = repository.entrants(g.id());
        if (entrants.isEmpty()) return List.of();
        // only people who are still here (and still hold the required role) can win
        List<Long> eligible = new ArrayList<>();
        try {
            for (Member member : guild.retrieveMembersByIds(false, entrants.stream().mapToLong(Long::longValue).toArray()).get()) {
                if (g.requiredRoleId() != null && member.getRoles().stream().noneMatch(r -> r.getIdLong() == g.requiredRoleId())) continue;
                eligible.add(member.getIdLong());
            }
        } catch (Exception e) {
            log.warn("Couldn't check who is still in the server for giveaway {}; drawing from everyone who entered", g.id(), e);
            eligible = entrants;
        }
        return GiveawayDraw.pick(eligible, g.winnerCount(), exclude, random);
    }

    private void show(Guild guild, Giveaway g, List<Long> winners, boolean reroll) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, g.channelId());
        if (channel == null || g.messageId() == null) return;
        channel.editMessageById(g.messageId(), GiveawayMessage.edit(g, repository.entryCount(g.id()))).queue(ok -> {}, error -> {});
        if (!channel.canTalk()) return;
        String text = winners.isEmpty()
                ? "Nobody entered **" + g.prize() + "**, so there is no winner."
                : (reroll ? "🎉 New winner" : "🎉 Congratulations") + (winners.size() == 1 ? " " : "s ") + GiveawayDraw.mentions(winners) + "! You won **" + g.prize() + "**.";
        channel.sendMessage(text).setAllowedMentions(EnumSet.of(Message.MentionType.USER)).mentionUsers(winners.stream().mapToLong(Long::longValue).toArray())
                .setMessageReference(g.messageId()).failOnInvalidReply(false).queue(ok -> {}, error -> {});
    }

    void sweep(JDA jda) {
        try {
            for (Giveaway g : repository.due(Instant.now(), 20)) {
                Guild guild = jda.getGuildById(g.guildId());
                if (guild == null) continue;
                try {
                    end(guild, g);
                } catch (RuntimeException e) {
                    log.warn("Couldn't end giveaway {}", g.id(), e);
                    repository.finish(g.id(), List.of()); // don't retry forever
                }
            }
        } catch (Exception e) {
            log.error("Giveaway sweep failed", e);
        }
    }
}
