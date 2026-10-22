package com.younglings.bot.absence;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Leave-of-absence notices (T-190): whether a server uses them, and each member's one current notice. The on/off switch is cached per server. */
@BService
public class AbsenceRepository {
    private static final Logger log = LoggerFactory.getLogger(AbsenceRepository.class);

    /** One member's notice: away until the end of {@code until} (UTC). */
    public record Notice(long guildId, long userId, LocalDate until, String reason, OffsetDateTime createdAt) {}

    private final ConnectionSupplier connectionSupplier;
    private final Map<Long, Boolean> enabled = new ConcurrentHashMap<>();

    public AbsenceRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "absence", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.absence_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.absence_notice (
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    until DATE NOT NULL,
                    reason TEXT NOT NULL DEFAULT '',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, user_id)
                );
                """));
    }

    public boolean enabled(long guildId) {
        return enabled.computeIfAbsent(guildId, this::loadEnabled);
    }

    private boolean loadEnabled(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled FROM younglings.absence_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            log.warn("Could not read the absence switch for {}", guildId, e);
            return false;
        }
    }

    public void setEnabled(long guildId, boolean on) throws SQLException {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.absence_config (guild_id, enabled, updated_at) VALUES (?, ?, NOW())
                     ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = NOW()
                     """)) {
            s.setLong(1, guildId);
            s.setBoolean(2, on);
            s.executeUpdate();
        }
        enabled.put(guildId, on);
    }

    /** Saves a member's notice, replacing the one they had. */
    public Notice save(long guildId, long userId, LocalDate until, String reason) throws SQLException {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.absence_notice (guild_id, user_id, until, reason, created_at) VALUES (?, ?, ?, ?, NOW())
                     ON CONFLICT (guild_id, user_id) DO UPDATE SET until = EXCLUDED.until, reason = EXCLUDED.reason, created_at = NOW()
                     RETURNING created_at
                     """)) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setDate(3, Date.valueOf(until));
            s.setString(4, reason);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return new Notice(guildId, userId, until, reason, rs.getObject(1, OffsetDateTime.class));
            }
        }
    }

    /** Ends a member's notice; false when they had none. */
    public boolean end(long guildId, long userId) throws SQLException {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.absence_notice WHERE guild_id = ? AND user_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            return s.executeUpdate() > 0;
        }
    }

    /** The member's notice if it still covers {@code today}. */
    public Optional<Notice> current(long guildId, long userId, LocalDate today) {
        return active(guildId, today).stream().filter(n -> n.userId() == userId).findFirst();
    }

    /** Every notice in a server that still covers {@code today}, soonest back first. */
    public List<Notice> active(long guildId, LocalDate today) {
        List<Notice> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT user_id, until, reason, created_at FROM younglings.absence_notice WHERE guild_id = ? AND until >= ? ORDER BY until, user_id")) {
            s.setLong(1, guildId);
            s.setDate(2, Date.valueOf(today));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(new Notice(guildId, rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getString(3), rs.getObject(4, OffsetDateTime.class)));
            }
        } catch (SQLException e) {
            log.warn("Could not read absence notices for {}", guildId, e);
        }
        return out;
    }

    /** Deletes notices that ended before {@code before}; returns how many. Kept a little after they end so "back since" can still be shown. */
    public int pruneEndedBefore(LocalDate before) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.absence_notice WHERE until < ?")) {
            s.setDate(1, Date.valueOf(before));
            return s.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not prune ended absence notices", e);
            return 0;
        }
    }
}
