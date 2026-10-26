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

    /**
     * The prize a Citadel wheel winner usually gets, so "Mark prize as given" can fill it in: {@code amount} (gp, null until set) and a
     * {@code note} for the coffer giveaway. {@code autoRecord} locks the form to these values (a tick box unlocks them for one prize).
     * Everything is off and empty until a server sets it.
     */
    public record PrizeDefaults(boolean autoRecord, Long amount, String note) {
        public static PrizeDefaults none() {
            return new PrizeDefaults(false, null, null);
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
                "ALTER TABLE younglings.citadel_wheel_settings ADD COLUMN IF NOT EXISTS video BOOLEAN NOT NULL DEFAULT FALSE;",
                // the default prize and "auto record to coffer"; off and empty unless a server sets them
                "ALTER TABLE younglings.citadel_wheel_settings ADD COLUMN IF NOT EXISTS prize_auto_record BOOLEAN NOT NULL DEFAULT FALSE;",
                "ALTER TABLE younglings.citadel_wheel_settings ADD COLUMN IF NOT EXISTS prize_amount BIGINT NULL;",
                "ALTER TABLE younglings.citadel_wheel_settings ADD COLUMN IF NOT EXISTS prize_note TEXT NULL;"));
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

    public PrizeDefaults getPrizeDefaults(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT prize_auto_record, prize_amount, prize_note FROM younglings.citadel_wheel_settings WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return PrizeDefaults.none();
                long amount = rs.getLong("prize_amount");
                boolean none = rs.wasNull();
                return new PrizeDefaults(rs.getBoolean("prize_auto_record"), none ? null : amount, rs.getString("prize_note"));
            }
        } catch (SQLException e) {
            log.error("Failed to read the wheel prize defaults of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the wheel prize defaults", e);
        }
    }

    /** Saves the prize defaults only, leaving the announcement settings as they are (a server with no row yet gets the default message). */
    public void savePrizeDefaults(long guildId, PrizeDefaults defaults) {
        String sql = """
                INSERT INTO younglings.citadel_wheel_settings (guild_id, message, prize_auto_record, prize_amount, prize_note, updated_at) VALUES (?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET prize_auto_record = EXCLUDED.prize_auto_record, prize_amount = EXCLUDED.prize_amount,
                    prize_note = EXCLUDED.prize_note, updated_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setString(2, WheelMessage.DEFAULT);
            s.setBoolean(3, defaults.autoRecord());
            if (defaults.amount() == null) s.setNull(4, Types.BIGINT);
            else s.setLong(4, defaults.amount());
            s.setString(5, defaults.note());
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the wheel prize defaults of guild {}", guildId, e);
            throw new RuntimeException("Failed to save the wheel prize defaults", e);
        }
    }
}
