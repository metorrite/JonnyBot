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

/**
 * A server's own branding: the name JonnyBot's messages use for the server, and what it set as the bot's profile there. Discord lets the bot
 * read its server avatar but not its server banner or bio back, so those two are remembered here to show on the dashboard.
 */
@BService
public class BrandingRepository {
    private static final Logger log = LoggerFactory.getLogger(BrandingRepository.class);

    /** What a server has chosen. {@code bannerSet} is whether a banner was uploaded here, since Discord won't say. */
    public record Profile(String displayName, String bio, boolean bannerSet) {
        static final Profile NONE = new Profile(null, null, false);
    }

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
                """,
                "ALTER TABLE younglings.guild_branding ADD COLUMN IF NOT EXISTS bio TEXT NULL;",
                "ALTER TABLE younglings.guild_branding ADD COLUMN IF NOT EXISTS banner_set BOOLEAN NOT NULL DEFAULT FALSE;"));
    }

    public Profile profile(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT display_name, bio, banner_set FROM younglings.guild_branding WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? new Profile(rs.getString(1), rs.getString(2), rs.getBoolean(3)) : Profile.NONE;
            }
        } catch (SQLException e) {
            log.error("Failed to read the branding of guild {}", guildId, e);
            return Profile.NONE;
        }
    }

    /** The custom display name, or {@code null} when the server has not set one. */
    public String displayName(long guildId) {
        return profile(guildId).displayName();
    }

    public void saveDisplayName(long guildId, String displayName) {
        upsert(guildId, "display_name", displayName == null ? null : displayName, Types.VARCHAR);
    }

    public void saveBio(long guildId, String bio) {
        upsert(guildId, "bio", bio, Types.VARCHAR);
    }

    public void saveBannerSet(long guildId, boolean set) {
        upsert(guildId, "banner_set", set, Types.BOOLEAN);
    }

    private void upsert(long guildId, String column, Object value, int type) {
        // The column name is one of this class's own constants, never user input.
        String sql = "INSERT INTO younglings.guild_branding (guild_id, " + column + ", updated_at) VALUES (?, ?, NOW()) "
                + "ON CONFLICT (guild_id) DO UPDATE SET " + column + " = EXCLUDED." + column + ", updated_at = NOW()";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            if (value == null) s.setNull(2, type); else s.setObject(2, value, type);
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the {} of guild {}", column, guildId, e);
            throw new RuntimeException("Failed to save the branding", e);
        }
    }
}
