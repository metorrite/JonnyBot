package com.younglings.bot.accountgate;

import com.younglings.bot.accountgate.AccountGateConfig.Action;
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
public class AccountGateRepository {
    private static final Logger log = LoggerFactory.getLogger(AccountGateRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public AccountGateRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "account gate", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.account_gate_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    min_age_days INTEGER NOT NULL DEFAULT 7,
                    action TEXT NOT NULL DEFAULT 'ALERT',
                    alert_channel_id BIGINT NULL
                );
                """));
    }

    /** This server's settings, or the defaults (switched off) if it has never saved any. */
    public AccountGateConfig get(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, min_age_days, action, alert_channel_id FROM younglings.account_gate_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return AccountGateConfig.defaults(guildId);
                long channel = rs.getLong("alert_channel_id");
                boolean none = rs.wasNull();
                return new AccountGateConfig(guildId, rs.getBoolean("enabled"), rs.getInt("min_age_days"), Action.valueOf(rs.getString("action")), none ? null : channel);
            }
        } catch (SQLException e) {
            log.error("Failed to read the new-account settings for guild {}", guildId, e);
            throw new RuntimeException("Failed to read the new-account settings", e);
        }
    }

    public void save(AccountGateConfig config) {
        String sql = """
                INSERT INTO younglings.account_gate_config (guild_id, enabled, min_age_days, action, alert_channel_id) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, min_age_days = EXCLUDED.min_age_days, action = EXCLUDED.action, alert_channel_id = EXCLUDED.alert_channel_id
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, config.guildId());
            s.setBoolean(2, config.enabled());
            s.setInt(3, config.minAgeDays());
            s.setString(4, config.action().name());
            if (config.alertChannelId() == null) s.setNull(5, Types.BIGINT); else s.setLong(5, config.alertChannelId());
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the new-account settings for guild {}", config.guildId(), e);
            throw new RuntimeException("Failed to save the new-account settings", e);
        }
    }
}
