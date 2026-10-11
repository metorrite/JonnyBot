package com.younglings.bot.autopurge;

import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Every ten minutes, deletes the messages that have outlived each channel's rule. A sweep looks at no more than {@link #PAGES} pages
 * of a hundred messages per channel, so a channel with a big backlog is cleared over several sweeps rather than in one burst.
 */
@BService
public class AutoPurgeService {
    private static final Logger log = LoggerFactory.getLogger(AutoPurgeService.class);
    static final int PAGES = 5;
    static final Permission[] NEEDED = {Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY, Permission.MESSAGE_MANAGE};

    private final AutoPurgeRepository repository;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor((ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "autopurge");
        thread.setDaemon(true);
        return thread;
    });

    public AutoPurgeService(AutoPurgeRepository repository) {
        this.repository = repository;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> sweep(jda), 2, 10, TimeUnit.MINUTES);
    }

    /** Whether JonnyBot can clean this channel, as words for the person setting it up (null when it can). */
    public static String problemWith(GuildMessageChannel channel) {
        Guild guild = channel.getGuild();
        if (!guild.getSelfMember().hasPermission(channel, NEEDED)) {
            return "JonnyBot needs the View Channel, Read Message History and Manage Messages permissions in #" + channel.getName() + " to clean it.";
        }
        return null;
    }

    void sweep(JDA jda) {
        try {
            for (AutoPurgeRule rule : repository.all()) {
                Guild guild = jda.getGuildById(rule.guildId());
                if (guild == null) continue;
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, rule.channelId());
                if (channel == null || problemWith(channel) != null) continue;
                try {
                    int removed = clean(channel, rule, Instant.now());
                    if (removed > 0) log.info("Auto-clean removed {} message(s) from #{} in guild {}", removed, channel.getName(), guild.getId());
                } catch (RuntimeException e) {
                    log.warn("Auto-clean of channel {} in guild {} failed", rule.channelId(), rule.guildId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Auto-clean sweep failed", e);
        }
    }

    int clean(GuildMessageChannel channel, AutoPurgeRule rule, Instant now) {
        long anchor = TimeUtil.getDiscordTimestamp(rule.cutoff(now).toEpochMilli());
        int removed = 0;
        for (int page = 0; page < PAGES; page++) {
            List<Message> batch = channel.getHistoryBefore(anchor, 100).complete().getRetrievedHistory();
            if (batch.isEmpty()) break;
            List<Message> due = new ArrayList<>();
            for (Message message : batch) {
                if (rule.shouldDelete(message.getTimeCreated().toInstant(), message.isPinned(), now)) due.add(message);
            }
            if (!due.isEmpty()) {
                // messages are returned newest first; waiting for the deletes keeps one sweep from piling up requests
                for (var future : channel.purgeMessages(due)) future.join();
                removed += due.size();
            }
            long oldest = batch.get(batch.size() - 1).getIdLong();
            // nothing past two weeks can be bulk deleted, so there is no point reading further back
            boolean reachedTooOld = batch.get(batch.size() - 1).getTimeCreated().toInstant().isBefore(now.minus(java.time.Duration.ofDays(14)));
            if (batch.size() < 100 || oldest >= anchor || reachedTooOld) break;
            anchor = oldest;
        }
        return removed;
    }
}
