package com.younglings.bot.wheel;

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

/** A server's settings for announcing the Citadel wheel's winner: whether to post it automatically, where, and the message. */
@BService
public class WheelSettingsRepository {
    private static final Logger log = LoggerFactory.getLogger(WheelSettingsRepository.class);

    /** {@code channelId} is null until a channel is chosen. */
    public record Settings(boolean autoPost, Long channelId, String message, boolean video) {
        /** The GIF, as it always was: the video form is an option a server switches on. */
        public Settings(boolean autoPost, Long channelId, String message) {
            this(autoPost, channelId, message, false);
        }

        public static Settings defaults() {
            return new Settings(false, null, WheelMessage.DEFAULT, false);
        }
    }

    private final ConnectionSupplier connectionSupplier;

    public WheelSettingsRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "citadel wheel", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.citadel_wheel_settings (
                    guild_id BIGINT PRIMARY KEY,
                    auto_post BOOLEAN NOT NULL DEFAULT FALSE,
                    channel_id BIGINT NULL,
                    message TEXT NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                // post the spin as an MP4 video instead of a GIF; off unless a server chooses it
                "ALTER TABLE younglings.citadel_wheel_settings ADD COLUMN IF NOT EXISTS video BOOLEAN NOT NULL DEFAULT FALSE;"));
    }

    public Settings get(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT auto_post, channel_id, message, video FROM younglings.citadel_wheel_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Settings.defaults();
                long channel = rs.getLong("channel_id");
                boolean none = rs.wasNull();
                return new Settings(rs.getBoolean("auto_post"), none ? null : channel, rs.getString("message"), rs.getBoolean("video"));
            }
        } catch (SQLException e) {
            log.error("Failed to read the wheel settings of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the wheel settings", e);
        }
    }

    public void save(long guildId, Settings settings) {
        String sql = """
                INSERT INTO younglings.citadel_wheel_settings (guild_id, auto_post, channel_id, message, video, updated_at) VALUES (?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET auto_post = EXCLUDED.auto_post, channel_id = EXCLUDED.channel_id, message = EXCLUDED.message, video = EXCLUDED.video, updated_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setBoolean(2, settings.autoPost());
            if (settings.channelId() == null) s.setNull(3, Types.BIGINT);
            else s.setLong(3, settings.channelId());
            s.setString(4, settings.message());
            s.setBoolean(5, settings.video());
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the wheel settings of guild {}", guildId, e);
            throw new RuntimeException("Failed to save the wheel settings", e);
        }
    }
}
