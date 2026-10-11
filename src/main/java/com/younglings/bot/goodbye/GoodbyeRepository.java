package com.younglings.bot.goodbye;

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
public class GoodbyeRepository {
    private static final Logger log = LoggerFactory.getLogger(GoodbyeRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public GoodbyeRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "goodbye", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.goodbye_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    channel_id BIGINT NULL,
                    content TEXT NOT NULL DEFAULT '',
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """));
    }

    /** This server's goodbye message, or the defaults (switched off) if it has never saved one. */
    public GoodbyeConfig get(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, channel_id, content FROM younglings.goodbye_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return GoodbyeConfig.defaults(guildId);
                long channel = rs.getLong("channel_id");
                boolean none = rs.wasNull();
                return new GoodbyeConfig(guildId, rs.getBoolean("enabled"), none ? null : channel, rs.getString("content"));
            }
        } catch (SQLException e) {
            log.error("Failed to read the goodbye message for guild {}", guildId, e);
            throw new RuntimeException("Failed to read the goodbye message", e);
        }
    }

    public void save(GoodbyeConfig config) {
        String sql = """
                INSERT INTO younglings.goodbye_config (guild_id, enabled, channel_id, content, updated_at) VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, channel_id = EXCLUDED.channel_id, content = EXCLUDED.content, updated_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, config.guildId());
            s.setBoolean(2, config.enabled());
            if (config.channelId() == null) s.setNull(3, Types.BIGINT); else s.setLong(3, config.channelId());
            s.setString(4, config.content());
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the goodbye message for guild {}", config.guildId(), e);
            throw new RuntimeException("Failed to save the goodbye message", e);
        }
    }
}
