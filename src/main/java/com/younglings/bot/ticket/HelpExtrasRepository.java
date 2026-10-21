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
                "ALTER TABLE younglings.help_extras ADD COLUMN IF NOT EXISTS related_tickets BOOLEAN NOT NULL DEFAULT FALSE;",
                "ALTER TABLE younglings.help_extras ADD COLUMN IF NOT EXISTS group_bosses TEXT NOT NULL DEFAULT 'Vorago, Solak';",
                "ALTER TABLE younglings.help_extras ADD COLUMN IF NOT EXISTS priority_tiers BOOLEAN NOT NULL DEFAULT FALSE;",
                "ALTER TABLE younglings.help_extras ADD COLUMN IF NOT EXISTS longtime_guest_days INTEGER NOT NULL DEFAULT 30;",
                "ALTER TABLE younglings.help_extras ADD COLUMN IF NOT EXISTS longtime_guest_pings BOOLEAN NOT NULL DEFAULT TRUE;",
                "ALTER TABLE younglings.help_extras ADD COLUMN IF NOT EXISTS longtime_guest_escalation_hours INTEGER NOT NULL DEFAULT 120;",
                "ALTER TABLE younglings.help_extras ADD COLUMN IF NOT EXISTS kc_requirements TEXT NOT NULL DEFAULT '';",
                // tickets linked as being about the same group content (stored once, smaller id first)
                """
                CREATE TABLE IF NOT EXISTS younglings.help_ticket_link (
                    ticket_a BIGINT NOT NULL,
                    ticket_b BIGINT NOT NULL,
                    linked_by BIGINT NOT NULL,
                    linked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (ticket_a, ticket_b)
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
             PreparedStatement s = c.prepareStatement("SELECT event_button, event_start_hours, event_days, related_tickets, group_bosses, priority_tiers, longtime_guest_days, longtime_guest_pings, longtime_guest_escalation_hours, kc_requirements FROM younglings.help_extras WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return HelpExtras.defaults(guildId);
                return new HelpExtras(guildId, rs.getBoolean("event_button"), rs.getInt("event_start_hours"), rs.getInt("event_days"), rs.getBoolean("related_tickets"), rs.getString("group_bosses"),
                        rs.getBoolean("priority_tiers"), rs.getInt("longtime_guest_days"), rs.getBoolean("longtime_guest_pings"), rs.getInt("longtime_guest_escalation_hours"), rs.getString("kc_requirements"));
            }
        } catch (SQLException e) {
            log.error("Failed to read the help extras of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the help extras", e);
        }
    }

    public void save(HelpExtras extras) {
        String sql = """
                INSERT INTO younglings.help_extras (guild_id, event_button, event_start_hours, event_days, related_tickets, group_bosses, priority_tiers, longtime_guest_days,
                    longtime_guest_pings, longtime_guest_escalation_hours, kc_requirements, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET event_button = EXCLUDED.event_button, event_start_hours = EXCLUDED.event_start_hours,
                    event_days = EXCLUDED.event_days, related_tickets = EXCLUDED.related_tickets, group_bosses = EXCLUDED.group_bosses, priority_tiers = EXCLUDED.priority_tiers,
                    longtime_guest_days = EXCLUDED.longtime_guest_days, longtime_guest_pings = EXCLUDED.longtime_guest_pings,
                    longtime_guest_escalation_hours = EXCLUDED.longtime_guest_escalation_hours, kc_requirements = EXCLUDED.kc_requirements, updated_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, extras.guildId());
            s.setBoolean(2, extras.eventButton());
            s.setInt(3, extras.eventStartHours());
            s.setInt(4, extras.eventDays());
            s.setBoolean(5, extras.relatedTickets());
            s.setString(6, extras.groupBosses());
            s.setBoolean(7, extras.priorityTiers());
            s.setInt(8, extras.longtimeGuestDays());
            s.setBoolean(9, extras.longtimeGuestPings());
            s.setInt(10, extras.longtimeGuestEscalationHours());
            s.setString(11, extras.kcRequirements());
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

    /** Links two tickets; false if they were already linked. */
    public boolean link(long ticketA, long ticketB, long linkedBy) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.help_ticket_link (ticket_a, ticket_b, linked_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            s.setLong(1, Math.min(ticketA, ticketB));
            s.setLong(2, Math.max(ticketA, ticketB));
            s.setLong(3, linkedBy);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to link two tickets", e);
        }
    }

    /** The tickets linked to this one. */
    public List<Long> linkedTo(long ticketId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT CASE WHEN ticket_a = ? THEN ticket_b ELSE ticket_a END AS other FROM younglings.help_ticket_link WHERE ticket_a = ? OR ticket_b = ? ORDER BY 1")) {
            s.setLong(1, ticketId);
            s.setLong(2, ticketId);
            s.setLong(3, ticketId);
            List<Long> out = new java.util.ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read linked tickets", e);
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
