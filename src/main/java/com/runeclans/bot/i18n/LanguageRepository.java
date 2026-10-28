package com.runeclans.bot.i18n;

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
import java.util.Optional;

/** The language each server chose for JonnyBot's messages. A server with no row uses {@link Messages#DEFAULT_LANGUAGE}. */
@BService
public class LanguageRepository {
    private static final Logger log = LoggerFactory.getLogger(LanguageRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public LanguageRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "language", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.guild_language (
                    guild_id BIGINT PRIMARY KEY,
                    language TEXT NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """));
    }

    /** The saved language code, or empty when the server never chose one (or the database could not be read). */
    public Optional<String> find(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT language FROM younglings.guild_language WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to read the language of guild {}", guildId, e);
            return Optional.empty();
        }
    }

    public void save(long guildId, String language) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.guild_language (guild_id, language, updated_at) VALUES (?, ?, NOW()) "
                     + "ON CONFLICT (guild_id) DO UPDATE SET language = EXCLUDED.language, updated_at = NOW()")) {
            s.setLong(1, guildId);
            s.setString(2, language);
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the language of guild {}", guildId, e);
            throw new RuntimeException("Failed to save the language", e);
        }
    }
}
