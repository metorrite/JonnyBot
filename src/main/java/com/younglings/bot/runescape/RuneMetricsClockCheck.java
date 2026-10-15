package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Watches the assumption behind {@link RuneMetricsDates}: that RuneMetrics' activity times are UK local time. An event can never be logged before it
 * happens, so if the activities saved over the last few days include ones whose time (read in that zone) is later than the moment we first saw them,
 * the zone is wrong. This is how the 2026-10-25 clock change will show up if RuneMetrics turns out to use a fixed UTC+1 rather than UK time: the check
 * logs a warning, and the zone can then be corrected with the {@code RUNEMETRICS_TIME_ZONE}
 * environment variable (for example {@code Etc/GMT-1}) and the stored times recomputed by emptying {@code occurred_at}. It only reads and logs.
 */
@BService
public class RuneMetricsClockCheck {
    private static final Logger log = LoggerFactory.getLogger(RuneMetricsClockCheck.class);
    private static final int MIN_SAMPLE = 20;
    private static final double TOLERATED_SHARE = 0.01;

    private final ConnectionSupplier connectionSupplier;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor((ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "runemetrics-clock-check");
        thread.setDaemon(true);
        return thread;
    });

    public RuneMetricsClockCheck(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        executor.scheduleWithFixedDelay(this::check, 5, 6 * 60, TimeUnit.MINUTES);
    }

    /** Whether {@code early} activities out of {@code total} "happening after we saw them" is more than a clock quirk. Pure, so it can be tested. */
    static boolean looksWrong(long early, long total) {
        return total >= MIN_SAMPLE && early > Math.max(1, Math.round(total * TOLERATED_SHARE));
    }

    void check() {
        String sql = """
                SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE occurred_at > recorded_at + INTERVAL '2 minutes') AS early
                FROM younglings.player_activity WHERE recorded_at >= NOW() - INTERVAL '3 days' AND occurred_at IS NOT NULL
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql); ResultSet rs = s.executeQuery()) {
            if (!rs.next()) return;
            long total = rs.getLong("total");
            long early = rs.getLong("early");
            if (looksWrong(early, total)) {
                log.warn("RuneMetrics clock check: {} of the last {} activities look like they happened after we first saw them, so the time zone assumed for their dates ({}) is probably wrong. "
                        + "If the UK clocks just changed, RuneMetrics may use a fixed offset: set RUNEMETRICS_TIME_ZONE (for example Etc/GMT-1) and clear occurred_at to recompute.", early, total, RuneMetricsDates.zone());
            } else {
                log.info("RuneMetrics clock check: {} of the last {} activities look early (zone {}), which is fine.", early, total, RuneMetricsDates.zone());
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("RuneMetrics clock check couldn't run", e);
        }
    }
}
