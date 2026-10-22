package com.younglings.bot.appeal;

import com.younglings.bot.appeal.AppealRules.Appeal;
import com.younglings.bot.appeal.AppealRules.Settings;
import com.younglings.bot.appeal.AppealRules.Status;
import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Ban and mute appeals (T-033): each server's appeal settings and the appeals people sent. */
@BService
public class AppealRepository {
    private static final Logger log = LoggerFactory.getLogger(AppealRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public AppealRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "appeals", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.appeal_setting (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    panel_id BIGINT NULL,
                    cooldown_days INT NOT NULL DEFAULT 7,
                    mirror_channel_id BIGINT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.appeal (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    case_no BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    user_name TEXT NOT NULL,
                    text TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'OPEN',
                    ticket_id BIGINT NULL,
                    mirror_message_id BIGINT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    decided_by BIGINT NULL,
                    decided_at TIMESTAMPTZ NULL,
                    note TEXT NULL
                );
                """,
                // one open appeal per case: a second one can't even be written
                "CREATE UNIQUE INDEX IF NOT EXISTS appeal_one_open ON younglings.appeal (guild_id, case_no) WHERE status = 'OPEN';",
                "CREATE INDEX IF NOT EXISTS appeal_guild ON younglings.appeal (guild_id, status, created_at DESC);",
                "CREATE INDEX IF NOT EXISTS appeal_user ON younglings.appeal (guild_id, user_id, case_no);"));
    }

    // ---------- settings ----------

    public Settings settings(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, panel_id, cooldown_days, mirror_channel_id FROM younglings.appeal_setting WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Settings.defaults(guildId);
                long panel = rs.getLong(2);
                Long panelId = rs.wasNull() ? null : panel;
                long mirror = rs.getLong(4);
                Long mirrorId = rs.wasNull() ? null : mirror;
                return new Settings(guildId, rs.getBoolean(1), panelId, rs.getInt(3), mirrorId);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the appeal settings", e);
        }
    }

    public void saveSettings(Settings settings) {
        String sql = """
                INSERT INTO younglings.appeal_setting (guild_id, enabled, panel_id, cooldown_days, mirror_channel_id) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, panel_id = EXCLUDED.panel_id, cooldown_days = EXCLUDED.cooldown_days,
                    mirror_channel_id = EXCLUDED.mirror_channel_id""";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, settings.guildId());
            s.setBoolean(2, settings.enabled());
            if (settings.panelId() == null) s.setNull(3, Types.BIGINT); else s.setLong(3, settings.panelId());
            s.setInt(4, settings.cooldownDays());
            if (settings.mirrorChannelId() == null) s.setNull(5, Types.BIGINT); else s.setLong(5, settings.mirrorChannelId());
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the appeal settings", e);
        }
    }

    // ---------- appeals ----------

    private static Appeal map(ResultSet rs) throws SQLException {
        long ticket = rs.getLong("ticket_id");
        Long ticketId = rs.wasNull() ? null : ticket;
        long mirror = rs.getLong("mirror_message_id");
        Long mirrorId = rs.wasNull() ? null : mirror;
        long by = rs.getLong("decided_by");
        Long decidedBy = rs.wasNull() ? null : by;
        Timestamp decided = rs.getTimestamp("decided_at");
        return new Appeal(rs.getLong("id"), rs.getLong("guild_id"), rs.getLong("case_no"), rs.getLong("user_id"), rs.getString("user_name"), rs.getString("text"),
                Status.valueOf(rs.getString("status")), ticketId, mirrorId, rs.getTimestamp("created_at").toInstant(), decidedBy, decided == null ? null : decided.toInstant(),
                rs.getString("note"));
    }

    private List<Appeal> list(String sql, Object... args) {
        List<Appeal> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Long l) s.setLong(i + 1, l);
                else if (args[i] instanceof Integer n) s.setInt(i + 1, n);
                else s.setString(i + 1, String.valueOf(args[i]));
            }
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read appeals", e);
        }
    }

    /** Writes a new open appeal; empty when the case already has an open one (someone pressed twice). */
    public Optional<Appeal> create(long guildId, long caseNo, long userId, String userName, String text) {
        String sql = """
                INSERT INTO younglings.appeal (guild_id, case_no, user_id, user_name, text) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING RETURNING *""";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, caseNo);
            s.setLong(3, userId);
            s.setString(4, userName);
            s.setString(5, text);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the appeal", e);
        }
    }

    public Optional<Appeal> find(long guildId, long id) {
        List<Appeal> rows = list("SELECT * FROM younglings.appeal WHERE guild_id = ? AND id = ?", guildId, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** A person's appeals for one case, newest first. */
    public List<Appeal> forCase(long guildId, long caseNo, long userId) {
        return list("SELECT * FROM younglings.appeal WHERE guild_id = ? AND case_no = ? AND user_id = ? ORDER BY created_at DESC", guildId, caseNo, userId);
    }

    /** A person's appeals in a server, newest first. */
    public List<Appeal> forUser(long guildId, long userId) {
        return list("SELECT * FROM younglings.appeal WHERE guild_id = ? AND user_id = ? ORDER BY created_at DESC LIMIT 50", guildId, userId);
    }

    /** A server's appeals for its dashboard: open ones first, then the latest decided. */
    public List<Appeal> forGuild(long guildId, int limit) {
        return list("SELECT * FROM younglings.appeal WHERE guild_id = ? ORDER BY (status = 'OPEN') DESC, created_at DESC LIMIT ?", guildId, limit);
    }

    public void setTicket(long id, long ticketId) {
        update("UPDATE younglings.appeal SET ticket_id = ? WHERE id = ?", ticketId, id);
    }

    public void setMirrorMessage(long id, long messageId) {
        update("UPDATE younglings.appeal SET mirror_message_id = ? WHERE id = ?", messageId, id);
    }

    private void update(String sql, long value, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, value);
            s.setLong(2, id);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update the appeal", e);
        }
    }

    /** Decides an open appeal. False when it wasn't open any more (someone else decided first). */
    public boolean decide(long guildId, long id, Status to, long by, String note) {
        String sql = "UPDATE younglings.appeal SET status = ?, decided_by = ?, decided_at = NOW(), note = ? WHERE guild_id = ? AND id = ? AND status = 'OPEN'";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, to.name());
            s.setLong(2, by);
            if (note == null || note.isBlank()) s.setNull(3, Types.VARCHAR); else s.setString(3, note);
            s.setLong(4, guildId);
            s.setLong(5, id);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to decide the appeal", e);
        }
    }

    /** Takes a decision back (the unban Discord refused after the appeal was marked): the appeal is open again. */
    public void reopen(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE younglings.appeal SET status = 'OPEN', decided_by = NULL, decided_at = NULL, note = NULL WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to reopen the appeal", e);
        }
    }
}
