package com.runeclans.bot.events;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The prize draws of an event (T-649): every full draw and every reroll, numbered from 0, with the seed, the entries it drew from and who it picked, so
 * the draw can be shown and checked later ({@link WinModes}). Rows are only ever added. A round's number is its key, so two draws started at the same
 * moment (the bot at the end time and a staff member pressing "Draw now") can't both be saved: the second is refused. The event repository is a
 * dependency only so its table exists first.
 */
@BService
public class EventDrawRepository {
    private static final Logger log = LoggerFactory.getLogger(EventDrawRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public EventDrawRepository(ConnectionSupplier connectionSupplier, EventRepository events) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "event prize draws", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.event_draw (
                    event_id BIGINT NOT NULL REFERENCES younglings.event (id) ON DELETE CASCADE,
                    round INT NOT NULL,
                    seed BIGINT NOT NULL,
                    entrants TEXT NOT NULL DEFAULT '[]',
                    picked TEXT NOT NULL DEFAULT '[]',
                    place INT NULL,
                    replaced TEXT NULL,
                    drawn_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    drawn_by BIGINT NULL,
                    PRIMARY KEY (event_id, round)
                );
                """));
    }

    /** Every round of the event's draws, oldest first. */
    public List<WinModes.Round> rounds(long eventId) {
        List<WinModes.Round> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event_draw WHERE event_id = ? ORDER BY round")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    int place = rs.getInt("place");
                    Integer placeOrNull = rs.wasNull() ? null : place;
                    long by = rs.getLong("drawn_by");
                    Long byOrNull = rs.wasNull() ? null : by;
                    list.add(new WinModes.Round(rs.getInt("round"), rs.getLong("seed"), entrantsOf(rs.getString("entrants")), pickedOf(rs.getString("picked")),
                            placeOrNull, rs.getString("replaced"), rs.getTimestamp("drawn_at").toInstant(), byOrNull));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the event's prize draws", e);
        }
    }

    /** Saves a round. False when a round with that number was already saved (someone else drew at the same moment). */
    public boolean add(long eventId, WinModes.Round r) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.event_draw (event_id, round, seed, entrants, picked, place, replaced, drawn_at, drawn_by) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            s.setLong(1, eventId);
            s.setInt(2, r.round());
            s.setLong(3, r.seed());
            s.setString(4, entrantsJson(r.entrants()).toString());
            s.setString(5, pickedJson(r.picked()).toString());
            if (r.place() == null) s.setNull(6, Types.INTEGER);
            else s.setInt(6, r.place());
            s.setString(7, r.replaced());
            s.setTimestamp(8, java.sql.Timestamp.from(r.at() == null ? Instant.now() : r.at()));
            if (r.by() == null) s.setNull(9, Types.BIGINT);
            else s.setLong(9, r.by());
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the prize draw", e);
        }
    }

    static DataArray entrantsJson(List<WinModes.Entrant> entrants) {
        DataArray a = DataArray.empty();
        for (WinModes.Entrant e : entrants) a.add(DataObject.empty().put("subject", e.subject()).put("tiles", e.tiles()).put("tickets", e.tickets()));
        return a;
    }

    static DataArray pickedJson(List<String> picked) {
        DataArray a = DataArray.empty();
        picked.forEach(a::add);
        return a;
    }

    static List<WinModes.Entrant> entrantsOf(String json) {
        List<WinModes.Entrant> list = new ArrayList<>();
        DataArray a = DataArray.fromJson(json == null || json.isBlank() ? "[]" : json);
        for (int i = 0; i < a.length(); i++) {
            DataObject o = a.getObject(i);
            list.add(new WinModes.Entrant(o.getString("subject"), o.getInt("tiles", 0), o.getInt("tickets", 0)));
        }
        return list;
    }

    static List<String> pickedOf(String json) {
        List<String> list = new ArrayList<>();
        DataArray a = DataArray.fromJson(json == null || json.isBlank() ? "[]" : json);
        for (int i = 0; i < a.length(); i++) list.add(a.getString(i));
        return list;
    }
}
