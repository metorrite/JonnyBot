package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Backs the weekly clan digest (joins/leaves, Citadel visits/caps) — see {@code WeeklyDigestService}. */
@BService
public class WeeklyDigestRepository {
    private static final Logger log = LoggerFactory.getLogger(WeeklyDigestRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public WeeklyDigestRepository(ConnectionSupplier connectionSupplier, RuneScapeDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    /** {@code eventType} is {@code "JOIN"} or {@code "LEAVE"}. */
    public void recordRosterEvent(long guildId, String rsn, String eventType) {
        String sql = "INSERT INTO younglings.clan_roster_event (guild_id, rsn, event_type) VALUES (?, ?, ?)";

        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setString(2, rsn);
            statement.setString(3, eventType);
            statement.executeUpdate();

        } catch (SQLException e) {
            log.error("Failed to record clan roster event for guild {} rsn {}", guildId, rsn, e);
            throw new RuntimeException("Failed to record clan roster event", e);
        }
    }

    public record RosterEvent(String rsn, String eventType, OffsetDateTime eventAt) {}

    public List<RosterEvent> getRosterEventsInWindow(long guildId, OffsetDateTime from, OffsetDateTime to) {
        String sql = """
                SELECT rsn, event_type, event_at FROM younglings.clan_roster_event
                WHERE guild_id = ? AND event_at >= ? AND event_at < ?
                ORDER BY event_at
                """;

        List<RosterEvent> results = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setTimestamp(2, Timestamp.from(from.toInstant()));
            statement.setTimestamp(3, Timestamp.from(to.toInstant()));

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new RosterEvent(rs.getString("rsn"), rs.getString("event_type"),
                            rs.getObject("event_at", OffsetDateTime.class)));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get clan roster events for guild {}", guildId, e);
            throw new RuntimeException("Failed to get clan roster events", e);
        }
    }

    public record CitadelActivityRow(String rsn, String activityText, String activityDate) {}

    /**
     * Every "Visited"/"Capped at my Clan Citadel" activity recorded (by {@code recorded_at}, when we
     * actually polled it — reliable) in the window, regardless of who's still a current clan member;
     * the caller cross-references against the live roster. {@code activityDate} is RuneMetrics' own raw
     * string, kept for the caller to parse for *relative* ordering within this one report — see
     * {@code RuneScapeDatabaseInitializer}'s player_activity comment for why it's never parsed into an
     * absolute timestamp anywhere else in this codebase (unknown timezone).
     */
    public List<CitadelActivityRow> getCitadelActivityInWindow(long guildId, OffsetDateTime from, OffsetDateTime to) {
        String sql = """
                SELECT rsn, activity_text, activity_date FROM younglings.player_activity
                WHERE guild_id = ? AND recorded_at >= ? AND recorded_at < ?
                  AND (activity_text LIKE 'Visited my Clan Citadel%' OR activity_text LIKE 'Capped at my Clan Citadel%')
                """;

        List<CitadelActivityRow> results = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, guildId);
            statement.setTimestamp(2, Timestamp.from(from.toInstant()));
            statement.setTimestamp(3, Timestamp.from(to.toInstant()));

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(new CitadelActivityRow(rs.getString("rsn"), rs.getString("activity_text"), rs.getString("activity_date")));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to get Citadel activity for guild {}", guildId, e);
            throw new RuntimeException("Failed to get Citadel activity", e);
        }
    }
}
