package com.younglings.bot.autopurge;

import com.younglings.bot.util.BotScheduler;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Every ten minutes, deletes the messages that have outlived each channel's rule. A sweep looks at no more than {@link #PAGES} pages
 * of a hundred messages per channel, so a channel with a big backlog is cleared over several sweeps rather than in one burst.
 */
@BService
public class AutoPurgeService {
    private static final Logger log = LoggerFactory.getLogger(AutoPurgeService.class);
    public static final int PAGES = 5;
    static final Permission[] NEEDED = {Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY, Permission.MESSAGE_MANAGE};

    private final AutoPurgeRepository repository;
    private final ScheduledExecutorService executor = BotScheduler.lane("autopurge");

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

    /** What a pass over a channel found: how many messages match the rule, how many it looked at, and whether it stopped before the end of what could be due. */
    public record Scan(int matched, int scanned, boolean mayHaveMore) {}

    int clean(GuildMessageChannel channel, AutoPurgeRule rule, Instant now) {
        return scan(channel, rule, now, true).matched();
    }

    /** The rule's facts about a message: whose it is, whether it has attachments, its text. A webhook counts as a bot. */
    static AutoPurgeRule.MessageFacts factsOf(Message message) {
        return new AutoPurgeRule.MessageFacts(message.getTimeCreated().toInstant(), message.isPinned(), message.getAuthor().isBot() || message.isWebhookMessage(),
                message.getAttachments().size(), message.getContentRaw());
    }

    /**
     * Goes through the channel's due messages exactly the way a sweep does. With {@code delete} false it only counts (the dashboard's "check first"
     * button), so what is previewed is what the next sweep would remove, and nothing is deleted.
     */
    public Scan scan(GuildMessageChannel channel, AutoPurgeRule rule, Instant now, boolean delete) {
        long anchor = TimeUtil.getDiscordTimestamp(rule.cutoff(now).toEpochMilli());
        int matched = 0;
        int scanned = 0;
        boolean more = false;
        for (int page = 0; page < PAGES; page++) {
            List<Message> batch = channel.getHistoryBefore(anchor, 100).complete().getRetrievedHistory();
            if (batch.isEmpty()) break;
            scanned += batch.size();
            List<Message> due = new ArrayList<>();
            for (Message message : batch) {
                if (rule.shouldDelete(factsOf(message), now)) due.add(message);
            }
            if (!due.isEmpty()) {
                if (delete) {
                    // messages are returned newest first; waiting for the deletes keeps one sweep from piling up requests
                    for (var future : channel.purgeMessages(due)) future.join();
                }
                matched += due.size();
            }
            long oldest = batch.get(batch.size() - 1).getIdLong();
            // nothing past two weeks can be bulk deleted, so there is no point reading further back
            boolean reachedTooOld = batch.get(batch.size() - 1).getTimeCreated().toInstant().isBefore(now.minus(java.time.Duration.ofDays(14)));
            if (batch.size() < 100 || oldest >= anchor || reachedTooOld) break;
            anchor = oldest;
            if (page == PAGES - 1) more = true;
        }
        return new Scan(matched, scanned, more);
    }
}
