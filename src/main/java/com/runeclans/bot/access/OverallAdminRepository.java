package com.runeclans.bot.access;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The overall RuneClans admins the bot's owner added by Discord user id (N5, runeclans.com/admin): one row per person, with who added them and
 * when. The built-in admins (the bot's owners, {@link OverallAdmins}) are never stored here.
 */
@BService
public class OverallAdminRepository {
    private static final Logger log = LoggerFactory.getLogger(OverallAdminRepository.class);

    /** One added admin. */
    public record Entry(long userId, String note, long addedBy, Instant addedAt) {}

    private final ConnectionSupplier connectionSupplier;

    public OverallAdminRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "overall admins", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.overall_admin (
                    user_id BIGINT PRIMARY KEY,
                    note TEXT NOT NULL DEFAULT '',
                    added_by BIGINT NOT NULL,
                    added_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """
        ));
    }

    public List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT user_id, note, added_by, added_at FROM younglings.overall_admin ORDER BY added_at, user_id")) {
            while (rs.next()) out.add(new Entry(rs.getLong("user_id"), rs.getString("note"), rs.getLong("added_by"), rs.getTimestamp("added_at").toInstant()));
        } catch (SQLException e) {
            log.error("Failed to read the overall admins", e);
            throw new RuntimeException("Failed to read the overall admins", e);
        }
        return out;
    }

    /** Adds the person, or changes their note when they are already listed (who added them and when stay the first ones). */
    public void put(long userId, String note, long addedBy) {
        String sql = """
                INSERT INTO younglings.overall_admin (user_id, note, added_by) VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET note = EXCLUDED.note
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, userId);
            s.setString(2, note);
            s.setLong(3, addedBy);
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to add overall admin {}", userId, e);
            throw new RuntimeException("Failed to add the overall admin", e);
        }
    }

    /** True when the person was listed. */
    public boolean delete(long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.overall_admin WHERE user_id = ?")) {
            s.setLong(1, userId);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            log.error("Failed to remove overall admin {}", userId, e);
            throw new RuntimeException("Failed to remove the overall admin", e);
        }
    }
}
