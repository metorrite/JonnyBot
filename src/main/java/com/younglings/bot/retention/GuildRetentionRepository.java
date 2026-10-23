package com.younglings.bot.retention;

import com.younglings.bot.database.SchemaBootstrapper;
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
import java.util.ArrayList;
import java.util.List;

/** Remembers when the bot left each server, and does the deleting of what a long-gone server left behind. */
@BService
public class GuildRetentionRepository {
    private static final Logger log = LoggerFactory.getLogger(GuildRetentionRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public GuildRetentionRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "guild retention", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                // When the bot was removed from a server. Cleared if it is added back before the data is deleted.
                """
                CREATE TABLE IF NOT EXISTS younglings.guild_departure (
                    guild_id BIGINT PRIMARY KEY,
                    left_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """));
    }

    public void recordDeparture(long guildId) {
        run("INSERT INTO younglings.guild_departure (guild_id, left_at) VALUES (?, now()) ON CONFLICT (guild_id) DO UPDATE SET left_at = now()", guildId);
    }

    /** The bot is back: nothing is deleted. */
    public void cancelDeparture(long guildId) {
        run("DELETE FROM younglings.guild_departure WHERE guild_id = ?", guildId);
    }

    /** Servers the bot left before {@code cutoff}. */
    public List<Long> departedBefore(Instant cutoff) {
        List<Long> ids = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT guild_id FROM younglings.guild_departure WHERE left_at < ? ORDER BY left_at")) {
            s.setTimestamp(1, Timestamp.from(cutoff));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
            return ids;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the departed servers", e);
        }
    }

    /** When the bot left the server, or null while it is there (or its data has already been deleted). */
    public Instant leftAt(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT left_at FROM younglings.guild_departure WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getTimestamp(1).toInstant() : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read when the bot left a server", e);
        }
    }

    /**
     * Every table that holds a server's own data: it has a server id that can never be empty. A table whose server id may be empty
     * holds data that belongs to a player, not a server (stat snapshots, say), and is left alone.
     */
    public List<String> serverTables() {
        String sql = """
                SELECT c.table_name FROM information_schema.columns c
                JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'
                WHERE c.table_schema = 'younglings' AND c.column_name = 'guild_id' AND c.is_nullable = 'NO'
                ORDER BY c.table_name
                """;
        List<String> tables = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql); ResultSet rs = s.executeQuery()) {
            while (rs.next()) tables.add(rs.getString(1));
            return tables;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list the server tables", e);
        }
    }

    /** How many rows a server has in a table. */
    public int count(String table, long guildId) throws SQLException {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings." + quote(table) + " WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Deletes a server's rows from one table (each call is its own transaction, so a refusal leaves everything else as it was). */
    public int delete(String table, long guildId) throws SQLException {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + quote(table) + " WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            return s.executeUpdate();
        }
    }

    /** Takes a server off the departed list once its data is gone. */
    public void finish(long guildId) {
        cancelDeparture(guildId);
    }

    /** A table name from the database's own catalogue, quoted so it can sit in a statement. */
    private static String quote(String table) {
        if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Unexpected table name: " + table);
        return "\"" + table + "\"";
    }

    private void run(String sql, long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Retention statement failed for guild {}", guildId, e);
        }
    }
}
