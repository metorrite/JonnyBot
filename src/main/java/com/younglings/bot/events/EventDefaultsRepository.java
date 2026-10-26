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
import java.util.List;

/** Each server's {@link EventDefaults}, one row per server; no row means the built-in defaults. */
@BService
public class EventDefaultsRepository {
    private static final Logger log = LoggerFactory.getLogger(EventDefaultsRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public EventDefaultsRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "event defaults", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.event_defaults (
                    guild_id BIGINT PRIMARY KEY,
                    settings TEXT NOT NULL DEFAULT '{}',
                    updated_by BIGINT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """));
    }

    public EventDefaults get(long guildId) {
        EventDefaults stored;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT settings FROM younglings.event_defaults WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                stored = rs.next() ? EventDefaults.fromStored(rs.getString(1)) : EventDefaults.NONE;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the event defaults", e);
        }
        return stored.withXpSource(xpSource(guildId));
    }

    /** The Clan plugin page's competition XP source (T-537), or null; a database without the plugin's table yet has none. */
    private String xpSource(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT competition_xp_source FROM younglings.plugin_guild_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            log.debug("No competition XP source for guild {}: {}", guildId, e.getMessage());
            return null;
        }
    }

    public void save(long guildId, EventDefaults defaults, long actorId) {
        String sql = """
                INSERT INTO younglings.event_defaults (guild_id, settings, updated_by, updated_at) VALUES (?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET settings = EXCLUDED.settings, updated_by = EXCLUDED.updated_by, updated_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setString(2, defaults.json().toString());
            s.setLong(3, actorId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the event defaults", e);
        }
    }
}
