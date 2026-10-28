package com.runeclans.bot.moderation;

import com.runeclans.bot.database.SchemaBootstrapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Moderation cases (numbered per server) and the moderation settings. */
@BService
public class ModCaseRepository {
    private static final Logger log = LoggerFactory.getLogger(ModCaseRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public ModCaseRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "moderation", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.mod_case_counter (
                    guild_id BIGINT PRIMARY KEY,
                    last_case BIGINT NOT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.mod_case (
                    guild_id BIGINT NOT NULL,
                    case_no BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    user_name TEXT NOT NULL,
                    moderator_id BIGINT NOT NULL,
                    moderator_name TEXT NOT NULL,
                    action TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    duration_minutes INTEGER NULL,
                    source TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    edited_by BIGINT NULL,
                    edited_at TIMESTAMPTZ NULL,
                    original_reason TEXT NULL,
                    PRIMARY KEY (guild_id, case_no)
                );
                """,
                "CREATE INDEX IF NOT EXISTS mod_case_user ON younglings.mod_case (guild_id, user_id, case_no DESC);",
                "CREATE INDEX IF NOT EXISTS mod_case_moderator ON younglings.mod_case (guild_id, moderator_id, case_no DESC);",
                """
                CREATE TABLE IF NOT EXISTS younglings.mod_setting (
                    guild_id BIGINT PRIMARY KEY,
                    dm_reason BOOLEAN NOT NULL DEFAULT TRUE,
                    show_moderator BOOLEAN NOT NULL DEFAULT FALSE,
                    appeal_url TEXT NULL,
                    protected_role_ids TEXT NOT NULL DEFAULT ''
                );
                """));
    }

    /** Writes a case with the server's next number (numbers are per server and never reused) and returns it. */
    public ModCase record(long guildId, long userId, String userName, long moderatorId, String moderatorName, ModAction action, String reason,
                          Integer durationMinutes, String source) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                long number;
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT INTO younglings.mod_case_counter (guild_id, last_case) VALUES (?, 1)
                        ON CONFLICT (guild_id) DO UPDATE SET last_case = younglings.mod_case_counter.last_case + 1
                        RETURNING last_case""")) {
                    s.setLong(1, guildId);
                    try (ResultSet rs = s.executeQuery()) {
                        rs.next();
                        number = rs.getLong(1);
                    }
                }
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT INTO younglings.mod_case (guild_id, case_no, user_id, user_name, moderator_id, moderator_name, action, reason, duration_minutes, source)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                    s.setLong(1, guildId);
                    s.setLong(2, number);
                    s.setLong(3, userId);
                    s.setString(4, userName);
                    s.setLong(5, moderatorId);
                    s.setString(6, moderatorName);
                    s.setString(7, action.name());
                    s.setString(8, reason);
                    if (durationMinutes == null) s.setNull(9, Types.INTEGER); else s.setInt(9, durationMinutes);
                    s.setString(10, source);
                    s.executeUpdate();
                }
                c.commit();
                return find(guildId, number).orElseThrow();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the moderation case", e);
        }
    }

    public Optional<ModCase> find(long guildId, long caseNo) {
        List<ModCase> rows = list("SELECT * FROM younglings.mod_case WHERE guild_id = ? AND case_no = ?", guildId, caseNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<ModCase> recent(long guildId, int limit) {
        return list("SELECT * FROM younglings.mod_case WHERE guild_id = ? ORDER BY case_no DESC LIMIT ?", guildId, limit);
    }

    public List<ModCase> forUser(long guildId, long userId, int limit) {
        return list("SELECT * FROM younglings.mod_case WHERE guild_id = ? AND user_id = ? ORDER BY case_no DESC LIMIT ?", guildId, userId, limit);
    }

    public List<ModCase> byModerator(long guildId, long moderatorId, int limit) {
        return list("SELECT * FROM younglings.mod_case WHERE guild_id = ? AND moderator_id = ? ORDER BY case_no DESC LIMIT ?", guildId, moderatorId, limit);
    }

    /** Changes a case's reason, remembering who did it, when, and the reason as first written. False if the server has no such case. */
    public boolean editReason(long guildId, long caseNo, String reason, long editorId) {
        String sql = """
                UPDATE younglings.mod_case SET original_reason = COALESCE(original_reason, reason), reason = ?, edited_by = ?, edited_at = NOW()
                WHERE guild_id = ? AND case_no = ?""";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, reason);
            s.setLong(2, editorId);
            s.setLong(3, guildId);
            s.setLong(4, caseNo);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to edit the case", e);
        }
    }

    // ---- settings ----

    public ModSettings settings(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT dm_reason, show_moderator, appeal_url, protected_role_ids FROM younglings.mod_setting WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return ModSettings.defaults(guildId);
                List<Long> roles = new ArrayList<>();
                for (String part : rs.getString("protected_role_ids").split(",")) if (!part.isBlank()) roles.add(Long.parseLong(part.strip()));
                return new ModSettings(guildId, rs.getBoolean("dm_reason"), rs.getBoolean("show_moderator"), rs.getString("appeal_url"), List.copyOf(roles));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the moderation settings", e);
        }
    }

    public void saveSettings(ModSettings settings) {
        String sql = """
                INSERT INTO younglings.mod_setting (guild_id, dm_reason, show_moderator, appeal_url, protected_role_ids) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET dm_reason = EXCLUDED.dm_reason, show_moderator = EXCLUDED.show_moderator,
                    appeal_url = EXCLUDED.appeal_url, protected_role_ids = EXCLUDED.protected_role_ids""";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, settings.guildId());
            s.setBoolean(2, settings.dmReason());
            s.setBoolean(3, settings.showModerator());
            if (settings.appealUrl() == null) s.setNull(4, Types.VARCHAR); else s.setString(4, settings.appealUrl());
            s.setString(5, String.join(",", settings.protectedRoleIds().stream().map(String::valueOf).toList()));
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the moderation settings", e);
        }
    }

    private List<ModCase> list(String sql, Object... args) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Long l) s.setLong(i + 1, l);
                else if (args[i] instanceof Integer n) s.setInt(i + 1, n);
            }
            List<ModCase> out = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read moderation cases", e);
        }
    }

    private static ModCase map(ResultSet rs) throws SQLException {
        int duration = rs.getInt("duration_minutes");
        Integer durationOrNull = rs.wasNull() ? null : duration;
        long editedBy = rs.getLong("edited_by");
        Long editedByOrNull = rs.wasNull() ? null : editedBy;
        Timestamp editedAt = rs.getTimestamp("edited_at");
        return new ModCase(rs.getLong("guild_id"), rs.getLong("case_no"), rs.getLong("user_id"), rs.getString("user_name"), rs.getLong("moderator_id"),
                rs.getString("moderator_name"), ModAction.valueOf(rs.getString("action")), rs.getString("reason"), durationOrNull, rs.getString("source"),
                rs.getTimestamp("created_at").toInstant(), editedByOrNull, editedAt == null ? null : editedAt.toInstant(), rs.getString("original_reason"));
    }
}
