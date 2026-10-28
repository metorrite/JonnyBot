package com.runeclans.bot.lockdown;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@BService
public class LockdownRepository {
    private static final Logger log = LoggerFactory.getLogger(LockdownRepository.class);

    /** A server's lockdown set: the channels {@code /lockdown start} locks, and the roles that can still talk in them. */
    public record Config(long guildId, List<Long> channelIds, List<Long> keepTalkingRoleIds) {
        public static final int MAX_CHANNELS = 50;
        public static final int MAX_ROLES = 10;

        public static Config defaults(long guildId) {
            return new Config(guildId, List.of(), List.of());
        }
    }

    /** A channel that is locked right now, with what it looked like before so it can be put back exactly. */
    public record Lock(long guildId, long channelId, long lockedBy, Instant lockedAt, String reason, boolean lockdown, String snapshots) {}

    private final ConnectionSupplier connectionSupplier;

    public LockdownRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "lockdown", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.lockdown_config (
                    guild_id BIGINT PRIMARY KEY,
                    channel_ids BIGINT[] NOT NULL DEFAULT '{}',
                    keep_talking_role_ids BIGINT[] NOT NULL DEFAULT '{}'
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.channel_lock (
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    locked_by BIGINT NOT NULL,
                    locked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    reason TEXT NOT NULL DEFAULT '',
                    lockdown BOOLEAN NOT NULL DEFAULT FALSE,
                    snapshots TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY (guild_id, channel_id)
                );
                """));
    }

    public Config config(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT channel_ids, keep_talking_role_ids FROM younglings.lockdown_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Config.defaults(guildId);
                return new Config(guildId, ids(rs.getArray(1)), ids(rs.getArray(2)));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the lockdown settings", e);
        }
    }

    public void saveConfig(Config config) {
        String sql = """
                INSERT INTO younglings.lockdown_config (guild_id, channel_ids, keep_talking_role_ids) VALUES (?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET channel_ids = EXCLUDED.channel_ids, keep_talking_role_ids = EXCLUDED.keep_talking_role_ids
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, config.guildId());
            s.setArray(2, c.createArrayOf("BIGINT", config.channelIds().toArray()));
            s.setArray(3, c.createArrayOf("BIGINT", config.keepTalkingRoleIds().toArray()));
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the lockdown settings", e);
        }
    }

    /** Records a lock; false if the channel already has one (nothing is overwritten, so the original snapshot is never lost). */
    public boolean addLock(Lock lock) {
        String sql = "INSERT INTO younglings.channel_lock (guild_id, channel_id, locked_by, locked_at, reason, lockdown, snapshots) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, lock.guildId());
            s.setLong(2, lock.channelId());
            s.setLong(3, lock.lockedBy());
            s.setTimestamp(4, Timestamp.from(lock.lockedAt()));
            s.setString(5, lock.reason());
            s.setBoolean(6, lock.lockdown());
            s.setString(7, lock.snapshots());
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the lock", e);
        }
    }

    public Optional<Lock> lock(long guildId, long channelId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT locked_by, locked_at, reason, lockdown, snapshots FROM younglings.channel_lock WHERE guild_id = ? AND channel_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, channelId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(guildId, channelId, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the lock", e);
        }
    }

    public List<Lock> locks(long guildId) {
        List<Lock> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT channel_id, locked_by, locked_at, reason, lockdown, snapshots FROM younglings.channel_lock WHERE guild_id = ? ORDER BY locked_at")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(new Lock(guildId, rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getBoolean(5), rs.getString(6)));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list the locks", e);
        }
    }

    public void removeLock(long guildId, long channelId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.channel_lock WHERE guild_id = ? AND channel_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, channelId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear the lock", e);
        }
    }

    private static Lock map(long guildId, long channelId, ResultSet rs) throws SQLException {
        return new Lock(guildId, channelId, rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getBoolean(4), rs.getString(5));
    }

    private static List<Long> ids(Array array) throws SQLException {
        List<Long> ids = new ArrayList<>();
        if (array == null) return ids;
        for (Object id : (Object[]) array.getArray()) ids.add(((Number) id).longValue());
        return ids;
    }
}
