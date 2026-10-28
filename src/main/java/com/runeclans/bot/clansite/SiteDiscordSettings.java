package com.runeclans.bot.clansite;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Discord on a clan website (builder v3, T-365): which members asked not to be named in the "who's online" list, and the online modes. The
 * site's own Discord settings (the mode, the feeds' channels) are its connections now ({@link SiteConnectionStore}, N7); the old
 * {@code site_discord_setting.online_mode} column is kept only as the source of that store's one-time copy.
 */
@BService
public class SiteDiscordSettings {
    private static final Logger log = LoggerFactory.getLogger(SiteDiscordSettings.class);

    /** How much the site's online list shows: the one setting, on the site admin's Connections page. */
    public enum OnlineMode {
        OFF("off"), COUNT("count"), NAMES("names"), NAMES_AVATARS("names+avatars");

        private final String key;

        OnlineMode(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Optional<OnlineMode> parse(String raw) {
            if (raw == null) return Optional.empty();
            for (OnlineMode mode : values()) if (mode.key.equals(raw.strip())) return Optional.of(mode);
            return Optional.empty();
        }
    }

    private final ConnectionSupplier connectionSupplier;

    public SiteDiscordSettings(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "site discord", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.site_discord_setting (
                    guild_id BIGINT PRIMARY KEY,
                    online_mode TEXT NOT NULL DEFAULT 'off',
                    updated_by BIGINT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.site_online_hidden (
                    guild_id BIGINT NOT NULL,
                    discord_user_id BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, discord_user_id)
                );
                """));
    }

    /** Members who asked not to be named in the online widget; they are still counted. */
    public Set<Long> hiddenMembers(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT discord_user_id FROM younglings.site_online_hidden WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            Set<Long> hidden = new HashSet<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) hidden.add(rs.getLong(1));
            }
            return hidden;
        } catch (SQLException e) {
            throw fail("read members hidden from the online list", e);
        }
    }

    public boolean isHidden(long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT 1 FROM younglings.site_online_hidden WHERE guild_id = ? AND discord_user_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw fail("read a member's online-list choice", e);
        }
    }

    public void setHidden(long guildId, long userId, boolean hidden) {
        String sql = hidden
                ? "INSERT INTO younglings.site_online_hidden (guild_id, discord_user_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
                : "DELETE FROM younglings.site_online_hidden WHERE guild_id = ? AND discord_user_id = ?";
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save a member's online-list choice", e);
        }
    }

    private static RuntimeException fail(String action, SQLException e) {
        log.error("Failed to {}", action, e);
        return new RuntimeException("Failed to " + action, e);
    }
}
