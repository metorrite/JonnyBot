package com.runeclans.bot.lookup;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

@BService
public class LookupReminderRepository {
    private static final Logger log = LoggerFactory.getLogger(LookupReminderRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public LookupReminderRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "lookup reminders", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.lookup_reminder (
                    guild_id BIGINT NOT NULL,
                    kind TEXT NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    channel_id BIGINT NULL,
                    role_id BIGINT NULL,
                    last_key TEXT NOT NULL DEFAULT '',
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, kind)
                );
                """));
    }

    /** Every kind for the server, switched off where nothing is saved. */
    public List<LookupReminder> forGuild(long guildId) {
        List<LookupReminder> out = new ArrayList<>();
        for (ReminderKind kind : ReminderKind.values()) out.add(get(guildId, kind));
        return out;
    }

    public LookupReminder get(long guildId, ReminderKind kind) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, channel_id, role_id, last_key FROM younglings.lookup_reminder WHERE guild_id = ? AND kind = ?")) {
            s.setLong(1, guildId);
            s.setString(2, kind.key());
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? read(guildId, kind, rs) : LookupReminder.off(guildId, kind);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read a lookup reminder", e);
        }
    }

    /** All servers that have this reminder switched on and a channel chosen. */
    public List<LookupReminder> enabled(ReminderKind kind) {
        List<LookupReminder> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT guild_id, enabled, channel_id, role_id, last_key FROM younglings.lookup_reminder WHERE kind = ? AND enabled AND channel_id IS NOT NULL")) {
            s.setString(1, kind.key());
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(read(rs.getLong("guild_id"), kind, rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list the servers with the {} reminder on", kind.key(), e);
        }
        return out;
    }

    public void save(LookupReminder r) {
        String sql = """
                INSERT INTO younglings.lookup_reminder (guild_id, kind, enabled, channel_id, role_id, updated_at) VALUES (?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id, kind) DO UPDATE SET enabled = EXCLUDED.enabled, channel_id = EXCLUDED.channel_id, role_id = EXCLUDED.role_id, updated_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, r.guildId());
            s.setString(2, r.kind().key());
            s.setBoolean(3, r.enabled());
            if (r.channelId() == null) s.setNull(4, Types.BIGINT); else s.setLong(4, r.channelId());
            if (r.roleId() == null) s.setNull(5, Types.BIGINT); else s.setLong(5, r.roleId());
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the {} reminder of guild {}", r.kind().key(), r.guildId(), e);
            throw new RuntimeException("Failed to save the reminder", e);
        }
    }

    public void markPosted(long guildId, ReminderKind kind, String key) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.lookup_reminder SET last_key = ? WHERE guild_id = ? AND kind = ?")) {
            s.setString(1, key);
            s.setLong(2, guildId);
            s.setString(3, kind.key());
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to record a posted {} reminder for guild {}", kind.key(), guildId, e);
        }
    }

    private static LookupReminder read(long guildId, ReminderKind kind, ResultSet rs) throws SQLException {
        long channel = rs.getLong("channel_id");
        boolean noChannel = rs.wasNull();
        long role = rs.getLong("role_id");
        boolean noRole = rs.wasNull();
        return new LookupReminder(guildId, kind, rs.getBoolean("enabled"), noChannel ? null : channel, noRole ? null : role, rs.getString("last_key"));
    }
}
