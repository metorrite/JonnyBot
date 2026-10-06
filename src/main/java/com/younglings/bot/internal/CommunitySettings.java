package com.younglings.bot.internal;

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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small settings the website's community features need, kept in the database so they survive restarts: which
 * channel member-created polls are posted in, and when a poll is due to close itself. Poll close times live in
 * their own table (rather than on the poll) so the existing poll code is untouched.
 */
@BService
public class CommunitySettings {
    private static final Logger log = LoggerFactory.getLogger(CommunitySettings.class);

    private final ConnectionSupplier connectionSupplier;

    public CommunitySettings(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "community settings", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.site_setting (
                    guild_id BIGINT PRIMARY KEY,
                    poll_channel_id BIGINT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.poll_close_schedule (
                    poll_id BIGINT PRIMARY KEY,
                    closes_at TIMESTAMPTZ NOT NULL
                );
                """,
                // Whether the website's Events menu shows a "something new" bubble alongside the bell. Off unless an admin turns it on.
                """
                ALTER TABLE younglings.site_setting
                    ADD COLUMN IF NOT EXISTS nav_event_bubble BOOLEAN NOT NULL DEFAULT FALSE;
                """));
    }

    // ---------- the member-poll channel ----------

    public Long pollChannel(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT poll_channel_id FROM younglings.site_setting WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? (Long) rs.getObject(1) : null;
            }
        } catch (SQLException e) {
            throw fail("read the poll channel", e);
        }
    }

    public void setPollChannel(long guildId, Long channelId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.site_setting (guild_id, poll_channel_id) VALUES (?, ?)
                     ON CONFLICT (guild_id) DO UPDATE SET poll_channel_id = EXCLUDED.poll_channel_id""")) {
            s.setLong(1, guildId);
            if (channelId == null) s.setNull(2, Types.BIGINT); else s.setLong(2, channelId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save the poll channel", e);
        }
    }

    // ---------- the Events menu's "new" bubble ----------

    public boolean navEventBubble(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT nav_event_bubble FROM younglings.site_setting WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw fail("read the Events bubble setting", e);
        }
    }

    public void setNavEventBubble(long guildId, boolean on) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.site_setting (guild_id, nav_event_bubble) VALUES (?, ?)
                     ON CONFLICT (guild_id) DO UPDATE SET nav_event_bubble = EXCLUDED.nav_event_bubble""")) {
            s.setLong(1, guildId);
            s.setBoolean(2, on);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save the Events bubble setting", e);
        }
    }

    // ---------- polls that close themselves ----------

    public void scheduleClose(long pollId, OffsetDateTime at) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.poll_close_schedule (poll_id, closes_at) VALUES (?, ?)
                     ON CONFLICT (poll_id) DO UPDATE SET closes_at = EXCLUDED.closes_at""")) {
            s.setLong(1, pollId);
            s.setTimestamp(2, Timestamp.from(at.toInstant()));
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("schedule a poll to close", e);
        }
    }

    public void clearSchedule(long pollId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.poll_close_schedule WHERE poll_id = ?")) {
            s.setLong(1, pollId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("clear a poll's close time", e);
        }
    }

    /** Polls whose close time has passed. */
    public List<Long> due(OffsetDateTime now) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT poll_id FROM younglings.poll_close_schedule WHERE closes_at <= ?")) {
            s.setTimestamp(1, Timestamp.from(now.toInstant()));
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
            return ids;
        } catch (SQLException e) {
            throw fail("find polls due to close", e);
        }
    }

    /** Close times for the given polls (those without one are simply absent). */
    public Map<Long, OffsetDateTime> closesAt(List<Long> pollIds) {
        Map<Long, OffsetDateTime> result = new HashMap<>();
        if (pollIds.isEmpty()) return result;
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT poll_id, closes_at FROM younglings.poll_close_schedule WHERE poll_id = ANY (?)")) {
            s.setArray(1, c.createArrayOf("bigint", pollIds.toArray()));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) result.put(rs.getLong(1), rs.getObject(2, OffsetDateTime.class));
            }
            return result;
        } catch (SQLException e) {
            throw fail("read poll close times", e);
        }
    }

    private static RuntimeException fail(String action, SQLException e) {
        log.error("Failed to {}", action, e);
        return new RuntimeException("Failed to " + action, e);
    }
}
