package com.younglings.bot.runescape.polling;

import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.runescape.polling.PollTally.Counts;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The RuneMetrics budget over time. The engine counts the day's requests per tier in memory ({@link PollTally}); this saves them as one row per day per
 * tier (about four rows a day for the whole bot, no per-request rows and no player names) so the owner can see the history after a restart. The day so
 * far is saved every hour, and never replaced by a smaller number, so a restart in the middle of a day loses at most the last hour of that day.
 */
@BService
public class RuneMetricsBudget {
    private static final Logger log = LoggerFactory.getLogger(RuneMetricsBudget.class);
    static final int KEEP_DAYS = 90;

    /** One day's counts for one tier, as saved. */
    public record Row(LocalDate day, PollPriority priority, Counts counts) {}

    private final ConnectionSupplier connectionSupplier;
    private final PollCoordinator coordinator;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor((ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "runemetrics-budget");
        thread.setDaemon(true);
        return thread;
    });

    public RuneMetricsBudget(ConnectionSupplier connectionSupplier, PollCoordinator coordinator) {
        this.connectionSupplier = connectionSupplier;
        this.coordinator = coordinator;
        SchemaBootstrapper.run(connectionSupplier, log, "runemetrics budget", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.runemetrics_daily (
                    day DATE NOT NULL,
                    priority TEXT NOT NULL,
                    sent BIGINT NOT NULL DEFAULT 0,
                    reused BIGINT NOT NULL DEFAULT 0,
                    rate_limited BIGINT NOT NULL DEFAULT 0,
                    failed BIGINT NOT NULL DEFAULT 0,
                    gave_up BIGINT NOT NULL DEFAULT 0,
                    PRIMARY KEY (day, priority)
                );
                """));
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        executor.scheduleWithFixedDelay(this::flush, 10, 60, TimeUnit.MINUTES);
    }

    /** Saves the day so far, and starts a new day's counters when the UTC day has changed. Never throws. */
    public void flush() {
        try {
            PollTally.Day finished = coordinator.tally().rollIfNewDay();
            if (finished != null) save(finished);
            save(coordinator.tally().snapshot());
            prune();
        } catch (Exception e) {
            log.warn("Saving the RuneMetrics budget counters failed; they are tried again in an hour", e);
        }
    }

    /** The day so far (memory) on top of the saved days, newest first, at most {@code days} days. */
    public List<Row> history(int days) {
        Map<String, Row> byKey = new java.util.LinkedHashMap<>();
        String sql = "SELECT day, priority, sent, reused, rate_limited, failed, gave_up FROM younglings.runemetrics_daily WHERE day > ? ORDER BY day DESC, priority";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setDate(1, Date.valueOf(LocalDate.now(java.time.ZoneOffset.UTC).minusDays(days)));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    PollPriority priority = priority(rs.getString("priority"));
                    if (priority == null) continue;
                    LocalDate day = rs.getDate("day").toLocalDate();
                    byKey.put(day + "/" + priority, new Row(day, priority, new Counts(rs.getLong("sent"), rs.getLong("reused"), rs.getLong("rate_limited"), rs.getLong("failed"), rs.getLong("gave_up"))));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to read the RuneMetrics budget history", e);
        }
        PollTally.Day today = coordinator.tally().snapshot();
        today.byPriority().forEach((p, counts) -> byKey.merge(today.day() + "/" + p, new Row(today.day(), p, counts), RuneMetricsBudget::larger));
        List<Row> rows = new ArrayList<>(byKey.values());
        rows.sort(java.util.Comparator.comparing(Row::day).reversed().thenComparing(r -> r.priority().ordinal()));
        return rows;
    }

    /** The live counters win over a saved row of the same day unless the bot has restarted since (then the saved row may be the bigger). */
    static Row larger(Row saved, Row live) {
        Counts a = saved.counts();
        Counts b = live.counts();
        return new Row(saved.day(), saved.priority(), new Counts(Math.max(a.sent(), b.sent()), Math.max(a.reused(), b.reused()), Math.max(a.rateLimited(), b.rateLimited()),
                Math.max(a.failed(), b.failed()), Math.max(a.gaveUp(), b.gaveUp())));
    }

    private static PollPriority priority(String name) {
        try {
            return PollPriority.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void save(PollTally.Day day) throws SQLException {
        String sql = """
                INSERT INTO younglings.runemetrics_daily (day, priority, sent, reused, rate_limited, failed, gave_up) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (day, priority) DO UPDATE SET sent = GREATEST(runemetrics_daily.sent, EXCLUDED.sent), reused = GREATEST(runemetrics_daily.reused, EXCLUDED.reused),
                    rate_limited = GREATEST(runemetrics_daily.rate_limited, EXCLUDED.rate_limited), failed = GREATEST(runemetrics_daily.failed, EXCLUDED.failed),
                    gave_up = GREATEST(runemetrics_daily.gave_up, EXCLUDED.gave_up)
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (var e : new EnumMap<>(day.byPriority()).entrySet()) {
                Counts n = e.getValue();
                s.setDate(1, Date.valueOf(day.day()));
                s.setString(2, e.getKey().name());
                s.setLong(3, n.sent());
                s.setLong(4, n.reused());
                s.setLong(5, n.rateLimited());
                s.setLong(6, n.failed());
                s.setLong(7, n.gaveUp());
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    private void prune() throws SQLException {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.runemetrics_daily WHERE day < ?")) {
            s.setDate(1, Date.valueOf(LocalDate.now(java.time.ZoneOffset.UTC).minusDays(KEEP_DAYS)));
            s.executeUpdate();
        }
    }

    /** How long a window of this many minutes is, for the demand estimate. */
    static double requestsPerDay(long players, Duration window) {
        return window.isZero() ? 0 : players * (86_400.0 / window.toSeconds());
    }
}
