package com.younglings.bot.modlog;

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

@BService
public class ModLogRepository {
    private static final Logger log = LoggerFactory.getLogger(ModLogRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public ModLogRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "mod log", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.mod_log_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    channel_id BIGINT NULL,
                    log_bans BOOLEAN NOT NULL DEFAULT TRUE,
                    log_timeouts BOOLEAN NOT NULL DEFAULT TRUE
                );
                """));
    }

    /** This server's moderation log settings, or the defaults (switched off) if it has never saved any. */
    public ModLogConfig get(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, channel_id, log_bans, log_timeouts FROM younglings.mod_log_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return ModLogConfig.defaults(guildId);
                long channel = rs.getLong("channel_id");
                boolean none = rs.wasNull();
                return new ModLogConfig(guildId, rs.getBoolean("enabled"), none ? null : channel, rs.getBoolean("log_bans"), rs.getBoolean("log_timeouts"));
            }
        } catch (SQLException e) {
            log.error("Failed to read the moderation log settings for guild {}", guildId, e);
            throw new RuntimeException("Failed to read the moderation log settings", e);
        }
    }

    public void save(ModLogConfig config) {
        String sql = """
                INSERT INTO younglings.mod_log_config (guild_id, enabled, channel_id, log_bans, log_timeouts) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, channel_id = EXCLUDED.channel_id, log_bans = EXCLUDED.log_bans, log_timeouts = EXCLUDED.log_timeouts
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, config.guildId());
            s.setBoolean(2, config.enabled());
            if (config.channelId() == null) s.setNull(3, Types.BIGINT); else s.setLong(3, config.channelId());
            s.setBoolean(4, config.logBans());
            s.setBoolean(5, config.logTimeouts());
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the moderation log settings for guild {}", config.guildId(), e);
            throw new RuntimeException("Failed to save the moderation log settings", e);
        }
    }
}
