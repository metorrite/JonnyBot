package com.runeclans.bot.jobs;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * How far the dashboard's long-running jobs got, kept in Postgres (T-698, docs SCALING-STATE M6 / F4).
 *
 * <p>The website starts a job (an event's roles and channels, a wrap-up step, the clan role fix) and then asks for its progress every couple of
 * seconds. With more than one bot process behind the website, the next question can reach a process that didn't start the job, so the progress
 * lives here, one row per job slot ({@code scope} + {@code key}: an event, a server), holding the latest job of that slot.
 *
 * <p>Only one job runs per slot across every process: {@link #claim} takes the slot inside a transaction holding a {@code pg_advisory_xact_lock}
 * on it. A running job reports at least every {@link #HEARTBEAT}; one silent for {@link #STALE} (its process stopped) no longer blocks the slot
 * and reads as failed.
 */
@BService
public class JobRepository {
    private static final Logger log = LoggerFactory.getLogger(JobRepository.class);

    /** An event's roles, channels and wrap-up steps (key: the event id). */
    public static final String EVENT = "event";
    /** The clan role fix (key: the server id). */
    public static final String ROLE_FIX = "role-fix";

    /** How often a running job tells the table it is still alive. */
    public static final Duration HEARTBEAT = Duration.ofSeconds(30);
    /** A running job not heard from for this long has stopped (its process went away). */
    public static final Duration STALE = Duration.ofMinutes(2);
    /** The first key of this repository's advisory locks, so they never meet another feature's. */
    private static final int LOCK_SPACE = 0x1409;

    /** One job: {@code state} is running, done or failed; {@code message} says what it is doing or why it stopped. */
    public record Row(String kind, String state, int done, int total, int failed, String message, Instant startedAt, @Nullable Instant finishedAt,
                      Instant updatedAt) {
        public static Row starting(String kind, Instant now) {
            return new Row(kind, "running", 0, 0, 0, "Starting…", now, null, now);
        }

        public boolean running() {
            return state.equals("running");
        }

        /** Whether the job is running and was heard from within {@link #STALE} of {@code now}: it holds its slot. */
        public boolean live(Instant now) {
            return running() && updatedAt.isAfter(now.minus(STALE));
        }

        /** The row as the dashboard should see it at {@code now}: a running job that went silent shows as failed. */
        public Row seenAt(Instant now) {
            if (!running() || live(now)) return this;
            return new Row(kind, "failed", done, total, failed, "It stopped part way (the bot restarted). Start it again to finish.", startedAt, updatedAt, updatedAt);
        }
    }

    /** The answer to {@link #claim}: {@code won} with the new row, or not with the row of the job that holds the slot. */
    public record Claim(boolean won, Row row) {}

    private final @Nullable ConnectionSupplier connectionSupplier;
    private final ScheduledExecutorService heartbeat = BotScheduler.lane("job-heartbeat");
    Clock clock = Clock.systemUTC();

    /** {@code connectionSupplier} is null only for {@link #inMemory}. */
    public JobRepository(@Nullable ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        if (connectionSupplier == null) return;
        SchemaBootstrapper.run(connectionSupplier, log, "dashboard jobs", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                // One row per slot (an event, a server): the latest job there. Rows are overwritten, never piled up.
                """
                CREATE TABLE IF NOT EXISTS younglings.dashboard_job (
                    scope TEXT NOT NULL,
                    job_key BIGINT NOT NULL,
                    kind TEXT NOT NULL,
                    state TEXT NOT NULL,
                    done INT NOT NULL DEFAULT 0,
                    total INT NOT NULL DEFAULT 0,
                    failed INT NOT NULL DEFAULT 0,
                    message TEXT NOT NULL DEFAULT '',
                    started_at TIMESTAMPTZ NOT NULL,
                    finished_at TIMESTAMPTZ NULL,
                    updated_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (scope, job_key)
                );
                """
        ));
    }

    /** A store kept in this process's memory, for tests: the same rules, no database. */
    public static JobRepository inMemory(Clock clock) {
        JobRepository memory = new Memory();
        memory.clock = clock;
        return memory;
    }

    public Instant now() {
        return clock.instant();
    }

    /**
     * Takes the slot for a new job of {@code kind}, unless a live job holds it. Racing processes queue on the slot's advisory lock, so only one
     * wins. Throws when the database can't be reached (the caller refuses to start).
     */
    public Claim claim(String scope, long key, String kind) {
        Instant now = now();
        try (Connection c = connection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement lock = c.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
                    lock.setInt(1, LOCK_SPACE);
                    lock.setInt(2, (scope + ":" + key).hashCode());
                    lock.execute();
                }
                Optional<Row> current = read(c, scope, key);
                if (current.isPresent() && current.get().live(now)) {
                    c.rollback();
                    return new Claim(false, current.get());
                }
                Row row = Row.starting(kind, now);
                write(c, scope, key, row);
                c.commit();
                return new Claim(true, row);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not start the job: the database did not answer", e);
        }
    }

    /** Saves the job's progress (stamped now). A database error is logged, never thrown: the job itself carries on. */
    public void save(String scope, long key, Row row) {
        Row stamped = new Row(row.kind(), row.state(), row.done(), row.total(), row.failed(), row.message(), row.startedAt(), row.finishedAt(), now());
        try (Connection c = connection()) {
            write(c, scope, key, stamped);
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not save the progress of the {} job {}", scope, key, e);
        }
    }

    /** The latest job of the slot, as stored (see {@link Row#seenAt}), or empty. */
    public Optional<Row> latest(String scope, long key) {
        try (Connection c = connection()) {
            return read(c, scope, key);
        } catch (SQLException e) {
            log.warn("Could not read the {} job {}", scope, key, e);
            return Optional.empty();
        }
    }

    /** Keeps a running job's row fresh every {@link #HEARTBEAT} until the returned future is cancelled. */
    public ScheduledFuture<?> keepAlive(String scope, long key) {
        long every = HEARTBEAT.toSeconds();
        return heartbeat.scheduleAtFixedRate(() -> touch(scope, key), every, every, TimeUnit.SECONDS);
    }

    void touch(String scope, long key) {
        try (Connection c = connection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.dashboard_job SET updated_at = ? WHERE scope = ? AND job_key = ? AND state = 'running'")) {
            s.setTimestamp(1, Timestamp.from(now()));
            s.setString(2, scope);
            s.setLong(3, key);
            s.executeUpdate();
        } catch (SQLException | RuntimeException e) {
            log.debug("Could not refresh the {} job {}: {}", scope, key, e.getMessage());
        }
    }

    private Connection connection() throws SQLException {
        if (connectionSupplier == null) throw new SQLException("no database");
        return connectionSupplier.getConnection();
    }

    private static Optional<Row> read(Connection c, String scope, long key) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT kind, state, done, total, failed, message, started_at, finished_at, updated_at FROM younglings.dashboard_job WHERE scope = ? AND job_key = ?")) {
            s.setString(1, scope);
            s.setLong(2, key);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Timestamp finished = rs.getTimestamp("finished_at");
                return Optional.of(new Row(rs.getString("kind"), rs.getString("state"), rs.getInt("done"), rs.getInt("total"), rs.getInt("failed"), rs.getString("message"),
                        rs.getTimestamp("started_at").toInstant(), finished == null ? null : finished.toInstant(), rs.getTimestamp("updated_at").toInstant()));
            }
        }
    }

    private static void write(Connection c, String scope, long key, Row row) throws SQLException {
        String sql = """
                INSERT INTO younglings.dashboard_job (scope, job_key, kind, state, done, total, failed, message, started_at, finished_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (scope, job_key) DO UPDATE SET kind = EXCLUDED.kind, state = EXCLUDED.state, done = EXCLUDED.done, total = EXCLUDED.total,
                    failed = EXCLUDED.failed, message = EXCLUDED.message, started_at = EXCLUDED.started_at, finished_at = EXCLUDED.finished_at, updated_at = EXCLUDED.updated_at
                """;
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, scope);
            s.setLong(2, key);
            s.setString(3, row.kind());
            s.setString(4, row.state());
            s.setInt(5, row.done());
            s.setInt(6, row.total());
            s.setInt(7, row.failed());
            s.setString(8, row.message());
            s.setTimestamp(9, Timestamp.from(row.startedAt()));
            s.setTimestamp(10, row.finishedAt() == null ? null : Timestamp.from(row.finishedAt()));
            s.setTimestamp(11, Timestamp.from(row.updatedAt()));
            s.executeUpdate();
        }
    }

    /** {@link #inMemory}: the same claim rules on a map. */
    private static final class Memory extends JobRepository {
        private final Map<String, Row> rows = new ConcurrentHashMap<>();

        private Memory() {
            super(null);
        }

        @Override
        public synchronized Claim claim(String scope, long key, String kind) {
            Row current = rows.get(scope + ":" + key);
            if (current != null && current.live(now())) return new Claim(false, current);
            Row row = Row.starting(kind, now());
            rows.put(scope + ":" + key, row);
            return new Claim(true, row);
        }

        @Override
        public void save(String scope, long key, Row row) {
            rows.put(scope + ":" + key, new Row(row.kind(), row.state(), row.done(), row.total(), row.failed(), row.message(), row.startedAt(), row.finishedAt(), now()));
        }

        @Override
        public Optional<Row> latest(String scope, long key) {
            return Optional.ofNullable(rows.get(scope + ":" + key));
        }

        @Override
        void touch(String scope, long key) {
            rows.computeIfPresent(scope + ":" + key, (k, r) -> r.running() ? new Row(r.kind(), r.state(), r.done(), r.total(), r.failed(), r.message(), r.startedAt(), r.finishedAt(), now()) : r);
        }
    }
}
