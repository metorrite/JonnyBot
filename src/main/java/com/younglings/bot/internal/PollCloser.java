package com.younglings.bot.internal;

import com.younglings.bot.commands.poll.PollService;
import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Closes polls that were given an end time. Each minute it asks the database which are due, so a restart never
 * loses a deadline — a poll whose time passed while the bot was down is simply closed on the next tick.
 */
@BService
public class PollCloser {
    private static final Logger log = LoggerFactory.getLogger(PollCloser.class);

    private final CommunitySettings settings;
    private final PollService polls;
    private final BotConfig botConfig;
    private final SiteCache cache;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor((ThreadFactory) r -> {
        Thread t = new Thread(r, "poll-closer");
        t.setDaemon(true);
        return t;
    });

    public PollCloser(CommunitySettings settings, PollService polls, BotConfig botConfig, SiteCache cache) {
        this.settings = settings;
        this.polls = polls;
        this.botConfig = botConfig;
        this.cache = cache;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> {
            try {
                closeDue(jda);
            } catch (Exception e) {
                log.error("Closing due polls failed", e);
            }
        }, 45, 60, TimeUnit.SECONDS);
    }

    void closeDue(JDA jda) {
        Long guildId = botConfig.getGuildId();
        if (guildId == null) return;
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) return;

        for (long pollId : settings.due(OffsetDateTime.now())) {
            polls.closePoll(guild, pollId); // does nothing if it was already ended by hand
            settings.clearSchedule(pollId);
            cache.invalidate("polls");
            log.info("Closed poll {} at its scheduled end", pollId);
        }
    }
}
