package com.younglings.bot.events;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Saved templates: an event's setup kept under a name so a similar event can be made in a moment. Shown to admins as "saved templates"; the code calls them
 * blueprints so they aren't confused with {@link EventTemplate}, the kinds of event (bingo, competition, ...). A blueprint holds the answers except the name
 * and the dates (kept as how long the event runs and how soon it starts), and the prizes.
 */
@BService
public class EventBlueprintRepository {
    private static final Logger log = LoggerFactory.getLogger(EventBlueprintRepository.class);

    /** {@code body}, {@code timing} and {@code prizes} are JSON. */
    public record Blueprint(long id, long guildId, String name, String description, String template, String body, String timing, String prizes, long createdBy, Instant createdAt, Instant updatedAt) {}

    private final ConnectionSupplier connectionSupplier;

    public EventBlueprintRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "event saved templates", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.event_blueprint (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    template TEXT NOT NULL,
                    body TEXT NOT NULL DEFAULT '{}',
                    timing TEXT NOT NULL DEFAULT '{}',
                    prizes TEXT NOT NULL DEFAULT '[]',
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS event_blueprint_name ON younglings.event_blueprint (guild_id, LOWER(name));"));
    }

    private static Blueprint map(ResultSet rs) throws SQLException {
        return new Blueprint(rs.getLong("id"), rs.getLong("guild_id"), rs.getString("name"), rs.getString("description"), rs.getString("template"), rs.getString("body"), rs.getString("timing"),
                rs.getString("prizes"), rs.getLong("created_by"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static boolean duplicateName(SQLException e) {
        return "23505".equals(e.getSQLState());
    }

    /** Saves a new blueprint. Empty when the server already has one with that name (names are compared without regard to case). */
    public OptionalLong create(long guildId, long createdBy, String name, String description, String template, String body, String timing, String prizes) {
        String sql = "INSERT INTO younglings.event_blueprint (guild_id, name, description, template, body, timing, prizes, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setString(2, name);
            s.setString(3, description);
            s.setString(4, template);
            s.setString(5, body);
            s.setString(6, timing);
            s.setString(7, prizes);
            s.setLong(8, createdBy);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return OptionalLong.of(rs.getLong(1));
            }
        } catch (SQLException e) {
            if (duplicateName(e)) return OptionalLong.empty();
            throw new RuntimeException("Failed to save the template", e);
        }
    }

    public Optional<Blueprint> get(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event_blueprint WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the template", e);
        }
    }

    public List<Blueprint> list(long guildId) {
        List<Blueprint> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event_blueprint WHERE guild_id = ? ORDER BY LOWER(name), id")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the templates", e);
        }
    }

    public int count(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.event_blueprint WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count the templates", e);
        }
    }

    /** Renames a blueprint and changes its description. 0 when it doesn't exist, -1 when another one already has that name, 1 when done. */
    public int rename(long guildId, long id, String name, String description) {
        String sql = "UPDATE younglings.event_blueprint SET name = ?, description = ?, updated_at = NOW() WHERE guild_id = ? AND id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, name);
            s.setString(2, description);
            s.setLong(3, guildId);
            s.setLong(4, id);
            return s.executeUpdate();
        } catch (SQLException e) {
            if (duplicateName(e)) return -1;
            throw new RuntimeException("Failed to rename the template", e);
        }
    }

    public boolean delete(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event_blueprint WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the template", e);
        }
    }
}
