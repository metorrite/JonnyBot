package com.runeclans.bot.welcome;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Reads and writes {@link WelcomeCardConfig}, one row per server. A missing row reads as the defaults with the card off. */
@BService
public class WelcomeCardRepository {
    private static final Logger log = LoggerFactory.getLogger(WelcomeCardRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public WelcomeCardRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "welcome card", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.welcome_card_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    background_color INTEGER NOT NULL DEFAULT 1974832,
                    background_image_url TEXT NOT NULL DEFAULT '',
                    text_color INTEGER NOT NULL DEFAULT 16777215,
                    title TEXT NOT NULL DEFAULT '',
                    description TEXT NOT NULL DEFAULT '',
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """));
    }

    public WelcomeCardConfig get(long guildId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM younglings.welcome_card_config WHERE guild_id = ?")) {
            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return WelcomeCardConfig.defaults(guildId);
                return new WelcomeCardConfig(guildId, rs.getBoolean("enabled"), rs.getInt("background_color"), rs.getString("background_image_url"),
                        rs.getInt("text_color"), rs.getString("title"), rs.getString("description"));
            }
        } catch (SQLException e) {
            log.error("Failed to read the welcome card for guild {}", guildId, e);
            throw new RuntimeException("Failed to read the welcome card", e);
        }
    }

    public void save(WelcomeCardConfig c) {
        String sql = """
                INSERT INTO younglings.welcome_card_config (guild_id, enabled, background_color, background_image_url, text_color, title, description, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, background_color = EXCLUDED.background_color,
                    background_image_url = EXCLUDED.background_image_url, text_color = EXCLUDED.text_color,
                    title = EXCLUDED.title, description = EXCLUDED.description, updated_at = NOW()
                """;
        try (Connection connection = connectionSupplier.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, c.guildId());
            statement.setBoolean(2, c.enabled());
            statement.setInt(3, c.backgroundColor());
            statement.setString(4, c.backgroundImageUrl());
            statement.setInt(5, c.textColor());
            statement.setString(6, c.title());
            statement.setString(7, c.description());
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the welcome card for guild {}", c.guildId(), e);
            throw new RuntimeException("Failed to save the welcome card", e);
        }
    }
}
