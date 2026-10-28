package com.runeclans.bot.plugin;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * "Show my clan that I use RuneSync" (T-140, PLUGIN-ERA-FEATURES.md section 1): a member's own switch, off by default, in
 * {@code plugin_member_prefs}. Only members who switched it on appear by name on the dashboard's Clan plugin page (staff only); everyone
 * else stays in the counts. The member sees their own switch on their profile's Plugin tab.
 */
@BService
public class PluginVisibilityRepository {
    private static final Logger log = LoggerFactory.getLogger(PluginVisibilityRepository.class);

    /** One member who chose to show it: their Discord id, and the RuneSync client and last check-in JonnyBot knows (null when none). */
    public record Shown(long discordUserId, String client, Instant lastSeen) {}

    private final ConnectionSupplier connectionSupplier;

    public PluginVisibilityRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "plugin member prefs", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.plugin_member_prefs (
                    discord_user_id BIGINT PRIMARY KEY,
                    show_to_clan BOOLEAN NOT NULL DEFAULT FALSE,
                    updated_at TIMESTAMPTZ NOT NULL
                );
                """));
    }

    /** Whether the member chose to show their clan that they use RuneSync. False until they switch it on. */
    public boolean showToClan(long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT show_to_clan FROM younglings.plugin_member_prefs WHERE discord_user_id = ?")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the plugin visibility", e);
        }
    }

    public void setShowToClan(long userId, boolean on, Instant at) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.plugin_member_prefs (discord_user_id, show_to_clan, updated_at) VALUES (?, ?, ?)
                ON CONFLICT (discord_user_id) DO UPDATE SET show_to_clan = EXCLUDED.show_to_clan, updated_at = EXCLUDED.updated_at
                """)) {
            s.setLong(1, userId);
            s.setBoolean(2, on);
            s.setTimestamp(3, Timestamp.from(at));
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the plugin visibility", e);
        }
    }

    /** Of these members, the ones who chose to show it, with their client and last check-in (from {@code plugin_client_status}). */
    public List<Shown> shown(Collection<Long> userIds) {
        List<Shown> out = new ArrayList<>();
        if (userIds.isEmpty()) return out;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT p.discord_user_id, cs.client, cs.last_seen FROM younglings.plugin_member_prefs p
                LEFT JOIN younglings.plugin_client_status cs ON cs.discord_user_id = p.discord_user_id
                WHERE p.show_to_clan AND p.discord_user_id = ANY (?)
                ORDER BY cs.last_seen DESC NULLS LAST
                """)) {
            s.setArray(1, c.createArrayOf("bigint", userIds.toArray(new Long[0])));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    Timestamp seen = rs.getTimestamp(3);
                    out.add(new Shown(rs.getLong(1), rs.getString(2), seen == null ? null : seen.toInstant()));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read who shows their plugin", e);
        }
    }

    /** Part of "delete everything the plugin sent". */
    public void deleteFor(long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.plugin_member_prefs WHERE discord_user_id = ?")) {
            s.setLong(1, userId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the plugin visibility", e);
        }
    }
}
