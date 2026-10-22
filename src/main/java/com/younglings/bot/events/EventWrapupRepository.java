package com.younglings.bot.events;

import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.events.EventWrapup.Step;
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
import java.util.List;

/**
 * When an event finished and which wrap-up steps have been done for it ({@code event_wrapup}), so each step runs once whether an organiser did it or the
 * schedule did. The event repository is a dependency only so its table exists first.
 */
@BService
public class EventWrapupRepository {
    private static final Logger log = LoggerFactory.getLogger(EventWrapupRepository.class);

    /** The state of the steps, plus how the channels were dealt with ("archived", "compressed", "deleted") and when an export was last made. */
    public record Row(EventWrapup.State state, String channelsHow, Instant exportedAt) {}

    private final ConnectionSupplier connectionSupplier;

    public EventWrapupRepository(ConnectionSupplier connectionSupplier, EventRepository events) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "event wrap-up", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.event_wrapup (
                    event_id BIGINT PRIMARY KEY REFERENCES younglings.event (id) ON DELETE CASCADE,
                    finished_at TIMESTAMPTZ NULL,
                    locked_at TIMESTAMPTZ NULL,
                    roles_removed_at TIMESTAMPTZ NULL,
                    channels_done_at TIMESTAMPTZ NULL,
                    channels_how TEXT NULL,
                    exported_at TIMESTAMPTZ NULL
                );
                """));
    }

    public Row row(long eventId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event_wrapup WHERE event_id = ?")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return new Row(EventWrapup.State.NONE, null, null);
                return new Row(new EventWrapup.State(at(rs, "finished_at"), at(rs, "locked_at"), at(rs, "roles_removed_at"), at(rs, "channels_done_at")), rs.getString("channels_how"), at(rs, "exported_at"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the event's wrap-up", e);
        }
    }

    private static Instant at(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    /** Notes that the event has finished, once: later calls change nothing. Returns whether this call was the one that noted it. */
    public boolean noteFinished(long eventId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.event_wrapup (event_id, finished_at) VALUES (?, NOW()) ON CONFLICT (event_id) DO UPDATE SET finished_at = COALESCE(younglings.event_wrapup.finished_at, NOW()) WHERE younglings.event_wrapup.finished_at IS NULL")) {
            s.setLong(1, eventId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to note the event's end", e);
        }
    }

    /** An event that is reopened after it finished starts its wrap-up over. */
    public void clear(long eventId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event_wrapup WHERE event_id = ?")) {
            s.setLong(1, eventId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to reset the event's wrap-up", e);
        }
    }

    /** Records that a step was done (now), and for the channels step how. */
    public void done(long eventId, Step step, String how) {
        String column = switch (step) {
            case LOCK -> "locked_at";
            case ROLES -> "roles_removed_at";
            case CHANNELS -> "channels_done_at";
        };
        String sql = "INSERT INTO younglings.event_wrapup (event_id, finished_at, " + column + (step == Step.CHANNELS ? ", channels_how" : "") + ") VALUES (?, NOW(), NOW()" + (step == Step.CHANNELS ? ", ?" : "")
                + ") ON CONFLICT (event_id) DO UPDATE SET " + column + " = NOW()" + (step == Step.CHANNELS ? ", channels_how = EXCLUDED.channels_how" : "");
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            if (step == Step.CHANNELS) s.setString(2, how);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record a wrap-up step", e);
        }
    }

    public void exported(long eventId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.event_wrapup (event_id, exported_at) VALUES (?, NOW()) ON CONFLICT (event_id) DO UPDATE SET exported_at = NOW()")) {
            s.setLong(1, eventId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the export", e);
        }
    }
}
