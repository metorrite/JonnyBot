package com.younglings.bot.runeclans;

import com.younglings.bot.database.SchemaBootstrapper;
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

/**
 * Where JonnyBot is in the RuneClans hub's change feed: one row, so a restart carries on from the last entry instead of replaying up to 14 days
 * of plugin events into points and bingo. New table {@code younglings.runeclans_feed_cursor} (no existing table holds hub state; the
 * {@code hub_*} tables belong to the event hub).
 */
@BService
public class RuneClansFeedCursor {
    private static final Logger log = LoggerFactory.getLogger(RuneClansFeedCursor.class);
    private static final String KEY = "feed";

    private final ConnectionSupplier connectionSupplier;

    public RuneClansFeedCursor(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "runeclans", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.runeclans_feed_cursor (
                    name TEXT PRIMARY KEY,
                    feed_cursor TEXT NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """));
    }

    public Optional<String> load() {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT feed_cursor FROM younglings.runeclans_feed_cursor WHERE name = ?")) {
            s.setString(1, KEY);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the RuneClans feed cursor", e);
        }
    }

    public void save(String cursor) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("INSERT INTO younglings.runeclans_feed_cursor (name, feed_cursor) VALUES (?, ?) "
                     + "ON CONFLICT (name) DO UPDATE SET feed_cursor = EXCLUDED.feed_cursor, updated_at = NOW()")) {
            s.setString(1, KEY);
            s.setString(2, cursor);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the RuneClans feed cursor", e);
        }
    }
}
