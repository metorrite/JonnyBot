package com.younglings.bot.clansite;

import com.younglings.bot.database.SchemaBootstrapper;
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
 * Discord on a clan website (builder v3, T-365): how much the "who's online" widget may show, and which members asked not to be named in
 * it. Off until an admin picks a mode. The channel feed's channels are the server's website news channels ({@code site_news_channel}),
 * which are already per server.
 */
@BService
public class SiteDiscordSettings {
    private static final Logger log = LoggerFactory.getLogger(SiteDiscordSettings.class);

    /** How much the online widget may show; a widget never shows more than this, whatever its own settings say. */
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

    public OnlineMode onlineMode(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT online_mode FROM younglings.site_discord_setting WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? OnlineMode.parse(rs.getString(1)).orElse(OnlineMode.OFF) : OnlineMode.OFF;
            }
        } catch (SQLException e) {
            throw fail("read the online mode", e);
        }
    }

    public void saveOnlineMode(long guildId, OnlineMode mode, long actorId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO younglings.site_discord_setting (guild_id, online_mode, updated_by, updated_at) VALUES (?, ?, ?, NOW())
                     ON CONFLICT (guild_id) DO UPDATE SET online_mode = EXCLUDED.online_mode, updated_by = EXCLUDED.updated_by, updated_at = NOW()
                     """)) {
            s.setLong(1, guildId);
            s.setString(2, mode.key());
            s.setLong(3, actorId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw fail("save the online mode", e);
        }
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
