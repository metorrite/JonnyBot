package com.runeclans.bot.internal;

import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The rank-up advisor's weekly update (T-594) every half hour, on its own lane: turns each server's promotion week over soon after it is due,
 * so the congrats list is ready when staff open the page, and queues the weekly congrats post where the site's owner turned auto post on
 * ({@link SiteRankAdvisor#sweep}). Turning and posting are each once per week (claimed in the database), so a late or repeated tick, or a
 * second bot process, never does either twice. Only servers with rank rules are looked at, and nothing is posted while the bot isn't live.
 */
@BService
public class RankWeeklyScheduler {
    private static final Logger log = LoggerFactory.getLogger(RankWeeklyScheduler.class);

    private final SiteRankAdvisor advisor;
    private final ScheduledExecutorService lane = BotScheduler.lane("rank-weekly");

    public RankWeeklyScheduler(SiteRankAdvisor advisor) {
        this.advisor = advisor;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        lane.scheduleWithFixedDelay(() -> {
            try {
                int posted = advisor.sweep(jda, OffsetDateTime.now(ZoneOffset.UTC));
                if (posted > 0) log.info("Rank-up advisor: queued {} weekly congrats post(s)", posted);
            } catch (Exception e) {
                log.error("Rank-up advisor: the weekly update failed", e);
            }
        }, 3, 30, TimeUnit.MINUTES);
    }
}
