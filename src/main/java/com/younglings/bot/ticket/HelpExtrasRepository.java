package com.younglings.bot.ticket;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.OptionalLong;

/** Storage for {@link HelpExtras} and for the events made from tickets. */
@BService
public class HelpExtrasRepository {
    private static final Logger log = LoggerFactory.getLogger(HelpExtrasRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public HelpExtrasRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "help extras", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.help_extras (
                    guild_id BIGINT PRIMARY KEY,
                    event_button BOOLEAN NOT NULL DEFAULT FALSE,
                    event_start_hours INTEGER NOT NULL DEFAULT 24,
                    event_days INTEGER NOT NULL DEFAULT 1,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                // the event a ticket was turned into, so the button can't make a second one
                """
                CREATE TABLE IF NOT EXISTS younglings.help_ticket_event (
                    ticket_id BIGINT PRIMARY KEY,
                    event_id BIGINT NOT NULL,
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """));
    }

    public HelpExtras get(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT event_button, event_start_hours, event_days FROM younglings.help_extras WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return HelpExtras.defaults(guildId);
                return new HelpExtras(guildId, rs.getBoolean("event_button"), rs.getInt("event_start_hours"), rs.getInt("event_days"));
            }
        } catch (SQLException e) {
            log.error("Failed to read the help extras of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the help extras", e);
        }
    }

    public void save(HelpExtras extras) {
        String sql = """
                INSERT INTO younglings.help_extras (guild_id, event_button, event_start_hours, event_days, updated_at) VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET event_button = EXCLUDED.event_button, event_start_hours = EXCLUDED.event_start_hours,
                    event_days = EXCLUDED.event_days, updated_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, extras.guildId());
            s.setBoolean(2, extras.eventButton());
            s.setInt(3, extras.eventStartHours());
            s.setInt(4, extras.eventDays());
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the help extras of guild {}", extras.guildId(), e);
            throw new RuntimeException("Failed to save the help extras", e);
        }
    }

    /** The event this ticket was turned into, if it was (empty while one is still being made). */
    public OptionalLong eventOf(long ticketId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT event_id FROM younglings.help_ticket_event WHERE ticket_id = ? AND event_id > 0")) {
            s.setLong(1, ticketId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the event of a ticket", e);
        }
    }

    /** Claims the right to turn a ticket into an event (so two clicks can't make two); false if it is already claimed or done. Finish with {@link #setEvent}. */
    public boolean claimEvent(long ticketId, long createdBy) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.help_ticket_event (ticket_id, event_id, created_by) VALUES (?, 0, ?) ON CONFLICT (ticket_id) DO NOTHING")) {
            s.setLong(1, ticketId);
            s.setLong(2, createdBy);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to claim a ticket for an event", e);
        }
    }

    public void setEvent(long ticketId, long eventId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.help_ticket_event SET event_id = ? WHERE ticket_id = ?")) {
            s.setLong(1, eventId);
            s.setLong(2, ticketId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the event of a ticket", e);
        }
    }

    /** Frees a ticket to be turned into an event again (used when making the event failed after it was claimed). */
    public void forgetEvent(long ticketId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.help_ticket_event WHERE ticket_id = ?")) {
            s.setLong(1, ticketId);
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to free ticket {} to be made an event again", ticketId, e);
        }
    }
}
