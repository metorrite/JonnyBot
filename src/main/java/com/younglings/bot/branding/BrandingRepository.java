package com.younglings.bot.branding;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/** A server's own name for what JonnyBot posts under (a clan or community name that is not the in-game clan's). */
@BService
public class BrandingRepository {
    private static final Logger log = LoggerFactory.getLogger(BrandingRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public BrandingRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "branding", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.guild_branding (
                    guild_id BIGINT PRIMARY KEY,
                    display_name TEXT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """));
    }

    /** The custom display name, or {@code null} when the server has not set one. */
    public String displayName(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT display_name FROM younglings.guild_branding WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            log.error("Failed to read the display name of guild {}", guildId, e);
            return null;
        }
    }

    public void saveDisplayName(long guildId, String displayName) {
        String sql = """
                INSERT INTO younglings.guild_branding (guild_id, display_name, updated_at) VALUES (?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET display_name = EXCLUDED.display_name, updated_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            if (displayName == null) s.setNull(2, Types.VARCHAR); else s.setString(2, displayName);
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the display name of guild {}", guildId, e);
            throw new RuntimeException("Failed to save the display name", e);
        }
    }
}
