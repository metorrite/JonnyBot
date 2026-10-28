package com.runeclans.bot.clansite;

import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Once a day, deletes clan websites that have been switched off for 30 days (design W11), so a clan's words aren't kept for ever. */
@BService
public class ClanSiteCleanup {
    private static final Logger log = LoggerFactory.getLogger(ClanSiteCleanup.class);
    static final Duration KEEP_SWITCHED_OFF = Duration.ofDays(30);

    private final ClanSiteRepository repository;
    private final ScheduledExecutorService executor = BotScheduler.lane("clan-site-cleanup");

    public ClanSiteCleanup(ClanSiteRepository repository) {
        this.repository = repository;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        executor.scheduleWithFixedDelay(this::sweep, 15, 24 * 60, TimeUnit.MINUTES);
    }

    void sweep() {
        try {
            int deleted = repository.deleteSwitchedOffBefore(Instant.now().minus(KEEP_SWITCHED_OFF));
            if (deleted > 0) log.info("Deleted {} clan website(s) switched off for more than {} days", deleted, KEEP_SWITCHED_OFF.toDays());
        } catch (Exception e) {
            log.error("Clan website clean-up failed", e);
        }
    }
}
