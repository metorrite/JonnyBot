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
 * Closes finished clan seasons (T-596) every half hour, on its own lane: a season that ended is closed within half an hour of midnight UTC
 * on the first day of the next one, or soon after a restart. Closing is idempotent ({@link SiteSeasons#closeFinished}), so a late or repeated
 * tick, or a second bot process, never closes a season twice. Nothing runs while the bot's {@code CLAN_SEASONS} switch is off.
 */
@BService
public class SeasonScheduler {
    private static final Logger log = LoggerFactory.getLogger(SeasonScheduler.class);

    private final SiteSeasons seasons;
    private final ScheduledExecutorService lane = BotScheduler.lane("clan-seasons");

    public SeasonScheduler(SiteSeasons seasons) {
        this.seasons = seasons;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!seasons.switchedOn) {
            log.info("Clan seasons are off (CLAN_SEASONS), so no season will be closed.");
            return;
        }
        JDA jda = event.getJda();
        lane.scheduleWithFixedDelay(() -> {
            try {
                int closed = seasons.closeFinished(jda, OffsetDateTime.now(ZoneOffset.UTC));
                if (closed > 0) log.info("Clan seasons: closed {} season(s)", closed);
            } catch (Exception e) {
                log.error("Clan seasons: the season-end check failed", e);
            }
        }, 2, 30, TimeUnit.MINUTES);
    }
}
