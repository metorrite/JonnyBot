package com.younglings.bot.discord.jobs;

import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.discord.jobs.DiscordJob.State;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The Discord job outbox's table (T-456). Queueing is one insert, so a website request that queues a job answers at once. Taking jobs to run
 * uses {@code FOR UPDATE SKIP LOCKED}, so two bot processes (a deploy overlapping the old one) never run the same job twice.
 */
@BService
public class DiscordJobRepository {
    private static final Logger log = LoggerFactory.getLogger(DiscordJobRepository.class);
    private static final String COLUMNS = "id, kind, guild_id, actor_id, dedupe_key, payload, state, attempts, waits, last_error, result, next_attempt_at, created_at, updated_at";

    private final ConnectionSupplier connectionSupplier;

    public DiscordJobRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "discord jobs", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // One row per change the website asked of Discord. payload holds what the job needs; result what it answered. Finished rows are
                // kept a few days so the website can still read how a change ended, then swept.
                """
                CREATE TABLE IF NOT EXISTS younglings.discord_job (
                    id BIGSERIAL PRIMARY KEY,
                    kind TEXT NOT NULL,
                    guild_id BIGINT NOT NULL,
                    actor_id BIGINT NOT NULL,
                    dedupe_key TEXT NULL,
                    payload TEXT NOT NULL DEFAULT '{}',
                    state TEXT NOT NULL DEFAULT 'queued',
                    attempts INT NOT NULL DEFAULT 0,
                    waits INT NOT NULL DEFAULT 0,
                    last_error TEXT NULL,
                    result TEXT NULL,
                    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS discord_job_due ON younglings.discord_job (next_attempt_at) WHERE state IN ('queued', 'retrying');
                """,

                // at most one waiting job per key: a newer change of the same thing replaces the one not yet started
                """
                CREATE UNIQUE INDEX IF NOT EXISTS discord_job_waiting_key ON younglings.discord_job (dedupe_key)
                    WHERE dedupe_key IS NOT NULL AND state IN ('queued', 'retrying');
                """
        ));
    }

    /**
     * Queues a job, or, when a job with the same key is still waiting, gives it this payload instead (and starts it afresh). One statement,
     * so two requests at once can't queue the same thing twice.
     */
    public DiscordJob enqueue(String kind, long guildId, long actorId, String dedupe, DataObject payload) {
        String sql = "INSERT INTO younglings.discord_job (kind, guild_id, actor_id, dedupe_key, payload) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (dedupe_key) WHERE dedupe_key IS NOT NULL AND state IN ('queued', 'retrying') "
                + "DO UPDATE SET payload = EXCLUDED.payload, actor_id = EXCLUDED.actor_id, state = 'queued', attempts = 0, waits = 0, last_error = NULL, "
                + "next_attempt_at = now(), updated_at = now() RETURNING " + COLUMNS;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, kind);
            s.setLong(2, guildId);
            s.setLong(3, actorId);
            s.setString(4, dedupe);
            s.setString(5, payload.toString());
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return read(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not queue a " + kind + " job", e);
        }
    }

    public Optional<DiscordJob> get(long id) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM younglings.discord_job WHERE id = ?")) {
            s.setLong(1, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read job " + id, e);
        }
    }

    /**
     * The jobs with these ids that this person asked for (others are left out, as if they didn't exist); with a {@code guildId}, only those
     * about that server.
     */
    public List<DiscordJob> find(Collection<Long> ids, Long guildId, long actorId) {
        if (ids.isEmpty()) return List.of();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM younglings.discord_job WHERE id = ANY (?) AND (CAST(? AS BIGINT) IS NULL OR guild_id = ?) AND actor_id = ? ORDER BY id")) {
            s.setArray(1, c.createArrayOf("bigint", ids.toArray()));
            setGuild(s, 2, guildId);
            s.setLong(4, actorId);
            List<DiscordJob> jobs = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) jobs.add(read(rs));
            }
            return jobs;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read jobs", e);
        }
    }

    /** Takes up to {@code limit} jobs that are due, oldest first, and marks them running. */
    public List<DiscordJob> claimDue(int limit) {
        String sql = "UPDATE younglings.discord_job SET state = 'running', attempts = attempts + 1, updated_at = now() WHERE id IN ("
                + "SELECT id FROM younglings.discord_job WHERE state IN ('queued', 'retrying') AND next_attempt_at <= now() "
                + "ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING " + COLUMNS;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setInt(1, limit);
            List<DiscordJob> jobs = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) jobs.add(read(rs));
            }
            jobs.sort((a, b) -> Long.compare(a.id(), b.id()));
            return jobs;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not take due jobs", e);
        }
    }

    public void done(long id, DataObject result) {
        finish(id, "done", null, result);
    }

    public void failed(long id, String error) {
        finish(id, "failed", error, null);
    }

    private void finish(long id, String state, String error, DataObject result) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET state = ?, last_error = ?, result = ?, updated_at = now() WHERE id = ? AND state = 'running'")) {
            s.setString(1, state);
            s.setString(2, error);
            s.setString(3, result == null ? null : result.toString());
            s.setLong(4, id);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not finish job " + id, e);
        }
    }

    /**
     * Puts a running job back to wait until {@code at}. If a newer change with the same key was queued meanwhile, that one wins and this one
     * ends as done: the newer change already says what the end state should be.
     */
    public void retryAt(long id, Instant at, String error, boolean rateLimit) {
        try (Connection c = connectionSupplier.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET state = 'retrying', last_error = ?, next_attempt_at = ?, waits = waits + ?, updated_at = now() "
                    + "WHERE id = ? AND state = 'running' AND NOT EXISTS (SELECT 1 FROM younglings.discord_job o WHERE o.dedupe_key = discord_job.dedupe_key "
                    + "AND o.id <> discord_job.id AND o.state IN ('queued', 'retrying'))")) {
                s.setString(1, error);
                s.setTimestamp(2, Timestamp.from(at));
                s.setInt(3, rateLimit ? 1 : 0);
                s.setLong(4, id);
                if (s.executeUpdate() == 1) return;
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET state = 'done', last_error = 'Replaced by a newer change.', updated_at = now() WHERE id = ? AND state = 'running'")) {
                s.setLong(1, id);
                s.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not put job " + id + " back", e);
        }
    }

    /** Sends a failed job again from the start (the website's Retry). False when it isn't this person's failed job (in this server, when given). */
    public boolean requeue(long id, Long guildId, long actorId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET state = 'queued', attempts = 0, waits = 0, last_error = NULL, next_attempt_at = now(), updated_at = now() "
                     + "WHERE id = ? AND (CAST(? AS BIGINT) IS NULL OR guild_id = ?) AND actor_id = ? AND state = 'failed' AND (dedupe_key IS NULL OR NOT EXISTS (SELECT 1 FROM younglings.discord_job o "
                     + "WHERE o.dedupe_key = discord_job.dedupe_key AND o.state IN ('queued', 'retrying')))")) {
            s.setLong(1, id);
            setGuild(s, 2, guildId);
            s.setLong(4, actorId);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not requeue job " + id, e);
        }
    }

    /**
     * After a restart: jobs left running by the old process go back to waiting (for an idempotent kind) or end as failed with a plain reason
     * (a post might already be in the channel, so it is never sent twice by itself).
     */
    public int recover(Collection<String> idempotentKinds) {
        try (Connection c = connectionSupplier.getConnection()) {
            int n;
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET state = 'retrying', next_attempt_at = now(), updated_at = now(), "
                    + "last_error = 'The bot restarted while doing this; trying again.' WHERE state = 'running' AND kind = ANY (?)")) {
                s.setArray(1, c.createArrayOf("text", idempotentKinds.toArray()));
                n = s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET state = 'failed', updated_at = now(), "
                    + "last_error = 'The bot restarted while doing this, so it may or may not have happened. Check Discord before trying again.' WHERE state = 'running'")) {
                n += s.executeUpdate();
            }
            return n;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not recover jobs", e);
        }
    }

    /** Forgets finished jobs older than {@code before}. */
    public int sweep(Instant before) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.discord_job WHERE state IN ('done', 'failed') AND updated_at < ?")) {
            s.setTimestamp(1, Timestamp.from(before));
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not sweep jobs", e);
        }
    }

    /** Fills two placeholders, {@code (? IS NULL OR guild_id = ?)}, with an optional server. */
    private static void setGuild(PreparedStatement s, int index, Long guildId) throws SQLException {
        if (guildId == null) {
            s.setNull(index, java.sql.Types.BIGINT);
            s.setNull(index + 1, java.sql.Types.BIGINT);
        } else {
            s.setLong(index, guildId);
            s.setLong(index + 1, guildId);
        }
    }

    private static DiscordJob read(ResultSet rs) throws SQLException {
        String result = rs.getString("result");
        return new DiscordJob(rs.getLong("id"), rs.getString("kind"), rs.getLong("guild_id"), rs.getLong("actor_id"), rs.getString("dedupe_key"),
                DataObject.fromJson(rs.getString("payload")), State.ofKey(rs.getString("state")).orElse(State.FAILED), rs.getInt("attempts"), rs.getInt("waits"),
                rs.getString("last_error"), result == null ? null : DataObject.fromJson(result), instant(rs, "next_attempt_at"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
