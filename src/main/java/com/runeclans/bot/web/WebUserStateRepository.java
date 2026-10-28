package com.runeclans.bot.web;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Storage for {@link WebUserState}: one row per (user, scope, key), the value as jsonb. */
@BService
public class WebUserStateRepository {
    private static final Logger log = LoggerFactory.getLogger(WebUserStateRepository.class);

    /** What a put did. */
    public enum Put { SAVED, TOO_MANY_KEYS }

    private final ConnectionSupplier connectionSupplier;

    public WebUserStateRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "website user state", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.web_user_state (
                    user_id BIGINT NOT NULL,
                    scope TEXT NOT NULL,
                    key TEXT NOT NULL,
                    value JSONB NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (user_id, scope, key)
                );
                """
        ));
    }

    /** The person's values in a scope, as JSON text by key: all of them, or only {@code keys} when it isn't empty. */
    public Map<String, String> get(long userId, String scope, List<String> keys) {
        String sql = "SELECT key, value::text FROM younglings.web_user_state WHERE user_id = ? AND scope = ?"
                + (keys.isEmpty() ? "" : " AND key = ANY (?)") + " ORDER BY key";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, userId);
            s.setString(2, scope);
            if (!keys.isEmpty()) s.setArray(3, c.createArrayOf("TEXT", keys.toArray()));
            Map<String, String> out = new LinkedHashMap<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
            return out;
        } catch (SQLException e) {
            log.error("Failed to read website user state", e);
            throw new RuntimeException("Failed to read website user state", e);
        }
    }

    /**
     * Saves one value ({@code json} is checked JSON text). A new key is refused when the person already has
     * {@link WebUserState#MAX_KEYS_PER_SCOPE} in that scope; changing an existing key always works.
     */
    public Put put(long userId, String scope, String key, String json) {
        String update = "UPDATE younglings.web_user_state SET value = ?::jsonb, updated_at = ? WHERE user_id = ? AND scope = ? AND key = ?";
        String insert = """
                INSERT INTO younglings.web_user_state (user_id, scope, key, value, updated_at)
                SELECT ?, ?, ?, ?::jsonb, ?
                WHERE (SELECT count(*) FROM younglings.web_user_state WHERE user_id = ? AND scope = ?) < ?
                ON CONFLICT (user_id, scope, key) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at
                """;
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection c = connectionSupplier.getConnection()) {
            try (PreparedStatement s = c.prepareStatement(update)) {
                s.setString(1, json);
                s.setTimestamp(2, now);
                s.setLong(3, userId);
                s.setString(4, scope);
                s.setString(5, key);
                if (s.executeUpdate() == 1) return Put.SAVED;
            }
            try (PreparedStatement s = c.prepareStatement(insert)) {
                s.setLong(1, userId);
                s.setString(2, scope);
                s.setString(3, key);
                s.setString(4, json);
                s.setTimestamp(5, now);
                s.setLong(6, userId);
                s.setString(7, scope);
                s.setInt(8, WebUserState.MAX_KEYS_PER_SCOPE);
                return s.executeUpdate() == 1 ? Put.SAVED : Put.TOO_MANY_KEYS;
            }
        } catch (SQLException e) {
            log.error("Failed to save website user state", e);
            throw new RuntimeException("Failed to save website user state", e);
        }
    }

    /** Removes one value. True if there was one. */
    public boolean delete(long userId, String scope, String key) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.web_user_state WHERE user_id = ? AND scope = ? AND key = ?")) {
            s.setLong(1, userId);
            s.setString(2, scope);
            s.setString(3, key);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete website user state", e);
            throw new RuntimeException("Failed to delete website user state", e);
        }
    }

    /** Everything kept for this person, in every scope (their "delete my data"). Returns how many values went. */
    public int deleteAll(long userId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.web_user_state WHERE user_id = ?")) {
            s.setLong(1, userId);
            return s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete a person's website user state", e);
            throw new RuntimeException("Failed to delete website user state", e);
        }
    }
}
