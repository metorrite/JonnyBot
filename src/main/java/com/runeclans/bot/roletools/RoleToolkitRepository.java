package com.runeclans.bot.roletools;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.roletools.RoleRules.Mode;
import com.runeclans.bot.roletools.RoleRules.Status;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Storage for the role toolkit: which roles each permission group may give and take, and the bulk jobs with the members each one will change. */
@BService
public class RoleToolkitRepository {
    private static final Logger log = LoggerFactory.getLogger(RoleToolkitRepository.class);

    public record Job(long id, long guildId, Mode mode, long sourceRoleId, long targetRoleId, long createdBy, Status status, int total, int done, int failed, Instant createdAt) {}

    private static final String JOB_COLUMNS = "id, guild_id, mode, source_role_id, target_role_id, created_by, status, total, done, failed, created_at";

    private final ConnectionSupplier connectionSupplier;

    public RoleToolkitRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "role toolkit", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.role_toolkit_allow (
                    guild_id BIGINT NOT NULL,
                    group_key TEXT NOT NULL,
                    role_id BIGINT NOT NULL,
                    PRIMARY KEY (guild_id, group_key, role_id)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.role_bulk_job (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    mode TEXT NOT NULL,
                    source_role_id BIGINT NOT NULL,
                    target_role_id BIGINT NOT NULL,
                    created_by BIGINT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PLANNED',
                    total INT NOT NULL DEFAULT 0,
                    done INT NOT NULL DEFAULT 0,
                    failed INT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.role_bulk_job_member (
                    job_id BIGINT NOT NULL REFERENCES younglings.role_bulk_job (id) ON DELETE CASCADE,
                    user_id BIGINT NOT NULL,
                    state TEXT NOT NULL DEFAULT 'PENDING',
                    PRIMARY KEY (job_id, user_id)
                );
                """,
                // A restart never carries on changing roles by itself: a job that was running is paused until a person presses Resume.
                "UPDATE younglings.role_bulk_job SET status = 'PAUSED' WHERE status = 'RUNNING';"
        ));
    }

    // ---------- the allow-list ----------

    /** For each permission group key, the roles its members may give and take. */
    public Map<String, Set<Long>> allowed(long guildId) {
        Map<String, Set<Long>> allowed = new HashMap<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT group_key, role_id FROM younglings.role_toolkit_allow WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) allowed.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getLong(2));
            }
            return allowed;
        } catch (SQLException e) {
            throw fail("read the role allow-list", e);
        }
    }

    /** Replaces one group's list. */
    public void setAllowed(long guildId, String groupKey, Set<Long> roleIds) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM younglings.role_toolkit_allow WHERE guild_id = ? AND group_key = ?");
                 PreparedStatement insert = c.prepareStatement("INSERT INTO younglings.role_toolkit_allow (guild_id, group_key, role_id) VALUES (?, ?, ?)")) {
                delete.setLong(1, guildId);
                delete.setString(2, groupKey);
                delete.executeUpdate();
                for (long roleId : roleIds) {
                    insert.setLong(1, guildId);
                    insert.setString(2, groupKey);
                    insert.setLong(3, roleId);
                    insert.addBatch();
                }
                insert.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw fail("save the role allow-list", e);
        }
    }

    // ---------- jobs ----------

    /** Records a dry run: the job (PLANNED) and the members it would change. Nothing about any role is changed. */
    public Job plan(long guildId, Mode mode, long sourceRoleId, long targetRoleId, long createdBy, List<Long> userIds) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement job = c.prepareStatement("INSERT INTO younglings.role_bulk_job (guild_id, mode, source_role_id, target_role_id, created_by, total) VALUES (?, ?, ?, ?, ?, ?) RETURNING id");
                 PreparedStatement member = c.prepareStatement("INSERT INTO younglings.role_bulk_job_member (job_id, user_id) VALUES (?, ?)")) {
                job.setLong(1, guildId);
                job.setString(2, mode.name());
                job.setLong(3, sourceRoleId);
                job.setLong(4, targetRoleId);
                job.setLong(5, createdBy);
                job.setInt(6, userIds.size());
                long id;
                try (ResultSet rs = job.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
                for (long userId : userIds) {
                    member.setLong(1, id);
                    member.setLong(2, userId);
                    member.addBatch();
                }
                member.executeBatch();
                c.commit();
                return get(guildId, id).orElseThrow();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw fail("plan the bulk job", e);
        }
    }

    public List<Job> jobs(long guildId, int limit) {
        List<Job> found = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT " + JOB_COLUMNS + " FROM younglings.role_bulk_job WHERE guild_id = ? ORDER BY id DESC LIMIT ?")) {
            s.setLong(1, guildId);
            s.setInt(2, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) found.add(read(rs));
            }
            return found;
        } catch (SQLException e) {
            throw fail("list the bulk jobs", e);
        }
    }

    public Optional<Job> get(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT " + JOB_COLUMNS + " FROM younglings.role_bulk_job WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw fail("read the bulk job", e);
        }
    }

    /** Moves a job to a new status only if it is still in {@code from}; returns whether it moved (so two presses of a button cannot both win). */
    public boolean move(long id, Status from, Status to) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.role_bulk_job SET status = ? WHERE id = ? AND status = ?")) {
            s.setString(1, to.name());
            s.setLong(2, id);
            s.setString(3, from.name());
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("move the bulk job", e);
        }
    }

    /** The members of a job still to do, up to {@code limit}. */
    public List<Long> pending(long jobId, int limit) {
        List<Long> ids = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT user_id FROM younglings.role_bulk_job_member WHERE job_id = ? AND state = 'PENDING' ORDER BY user_id LIMIT ?")) {
            s.setLong(1, jobId);
            s.setInt(2, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
            return ids;
        } catch (SQLException e) {
            throw fail("read the bulk job members", e);
        }
    }

    /** Records one member as done or failed and keeps the job's counters in step. */
    public void finishMember(long jobId, long userId, boolean ok) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement m = c.prepareStatement("UPDATE younglings.role_bulk_job_member SET state = ? WHERE job_id = ? AND user_id = ? AND state = 'PENDING'");
                 PreparedStatement j = c.prepareStatement("UPDATE younglings.role_bulk_job SET " + (ok ? "done = done + 1" : "failed = failed + 1") + " WHERE id = ?")) {
                m.setString(1, ok ? "DONE" : "FAILED");
                m.setLong(2, jobId);
                m.setLong(3, userId);
                if (m.executeUpdate() > 0) {
                    j.setLong(1, jobId);
                    j.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw fail("record a bulk job member", e);
        }
    }

    /** A page of the members a job would change, for the dry-run list. */
    public List<Long> members(long jobId, int limit) {
        List<Long> ids = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT user_id FROM younglings.role_bulk_job_member WHERE job_id = ? ORDER BY user_id LIMIT ?")) {
            s.setLong(1, jobId);
            s.setInt(2, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
            return ids;
        } catch (SQLException e) {
            throw fail("read the bulk job members", e);
        }
    }

    /** Whether the server already has a job running. */
    public boolean hasRunning(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT 1 FROM younglings.role_bulk_job WHERE guild_id = ? AND status = 'RUNNING' LIMIT 1")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw fail("check running bulk jobs", e);
        }
    }

    private static Job read(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new Job(rs.getLong("id"), rs.getLong("guild_id"), Mode.valueOf(rs.getString("mode")), rs.getLong("source_role_id"), rs.getLong("target_role_id"), rs.getLong("created_by"),
                Status.valueOf(rs.getString("status")), rs.getInt("total"), rs.getInt("done"), rs.getInt("failed"), created.toInstant());
    }

    private static RuntimeException fail(String what, SQLException e) {
        log.error("Failed to {}", what, e);
        return new RuntimeException("Failed to " + what, e);
    }
}
