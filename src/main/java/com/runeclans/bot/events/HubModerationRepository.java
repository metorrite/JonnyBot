package com.runeclans.bot.events;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Moderation of the events hub (T-218): reports people make about a listed event, and servers barred from listing events. Taking one event off the hub is
 * a flag in the event's own settings ({@code hub.takedown}, see {@link EventListing}); this keeps everything else.
 */
@BService
public class HubModerationRepository {
    private static final Logger log = LoggerFactory.getLogger(HubModerationRepository.class);

    public record Report(long id, long eventId, long guildId, String reason, String reporter, Instant createdAt) {}

    public record Barred(long guildId, String reason, long by, Instant at) {}

    private final ConnectionSupplier connectionSupplier;

    public HubModerationRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "events hub moderation", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_event_report (
                    id BIGSERIAL PRIMARY KEY,
                    event_id BIGINT NOT NULL,
                    guild_id BIGINT NOT NULL,
                    reason TEXT NOT NULL,
                    reporter TEXT NULL,
                    status TEXT NOT NULL DEFAULT 'OPEN',
                    resolved_by BIGINT NULL,
                    resolved_at TIMESTAMPTZ NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS hub_event_report_open ON younglings.hub_event_report (status, created_at);",
                """
                CREATE TABLE IF NOT EXISTS younglings.hub_barred_guild (
                    guild_id BIGINT PRIMARY KEY,
                    reason TEXT NOT NULL,
                    barred_by BIGINT NOT NULL,
                    barred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """));
    }

    /** Records a report unless the event was reported {@code maxPerDay} times in the last day already (a flood guard). Returns whether it was kept. */
    public boolean addReport(long eventId, long guildId, String reason, String reporter, int maxPerDay) {
        try (Connection c = connectionSupplier.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.hub_event_report WHERE event_id = ? AND created_at > NOW() - INTERVAL '1 day'")) {
                s.setLong(1, eventId);
                try (ResultSet rs = s.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= maxPerDay) return false;
                }
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.hub_event_report (event_id, guild_id, reason, reporter) VALUES (?, ?, ?, ?)")) {
                s.setLong(1, eventId);
                s.setLong(2, guildId);
                s.setString(3, reason);
                if (reporter == null) s.setNull(4, Types.VARCHAR);
                else s.setString(4, reporter);
                s.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the report", e);
        }
    }

    public List<Report> openReports() {
        List<Report> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.hub_event_report WHERE status = 'OPEN' ORDER BY created_at LIMIT 500");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                list.add(new Report(rs.getLong("id"), rs.getLong("event_id"), rs.getLong("guild_id"), rs.getString("reason"), rs.getString("reporter"), rs.getTimestamp("created_at").toInstant()));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the reports", e);
        }
    }

    /** Closes one open report as DISMISSED or ACTIONED. False when it isn't open (any more). */
    public boolean resolve(long id, String status, long by) {
        return update("UPDATE younglings.hub_event_report SET status = ?, resolved_by = ?, resolved_at = NOW() WHERE id = ? AND status = 'OPEN'", status, by, id) > 0;
    }

    /** Closes every open report about an event as ACTIONED (the event was taken off the hub). */
    public int resolveAllFor(long eventId, long by) {
        return update("UPDATE younglings.hub_event_report SET status = 'ACTIONED', resolved_by = ?, resolved_at = NOW() WHERE event_id = ? AND status = 'OPEN'", null, by, eventId);
    }

    private int update(String sql, String status, long by, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            int i = 1;
            if (status != null) s.setString(i++, status);
            s.setLong(i++, by);
            s.setLong(i, id);
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close the report", e);
        }
    }

    public List<Barred> barred() {
        List<Barred> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.hub_barred_guild ORDER BY barred_at DESC");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) list.add(new Barred(rs.getLong("guild_id"), rs.getString("reason"), rs.getLong("barred_by"), rs.getTimestamp("barred_at").toInstant()));
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the barred servers", e);
        }
    }

    public Set<Long> barredIds() {
        Set<Long> ids = new HashSet<>();
        barred().forEach(b -> ids.add(b.guildId()));
        return ids;
    }

    public void bar(long guildId, String reason, long by) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.hub_barred_guild (guild_id, reason, barred_by) VALUES (?, ?, ?)
                     ON CONFLICT (guild_id) DO UPDATE SET reason = EXCLUDED.reason, barred_by = EXCLUDED.barred_by, barred_at = NOW()
                     """)) {
            s.setLong(1, guildId);
            s.setString(2, reason);
            s.setLong(3, by);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to bar the server", e);
        }
    }

    public boolean lift(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.hub_barred_guild WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to lift the bar", e);
        }
    }
}
