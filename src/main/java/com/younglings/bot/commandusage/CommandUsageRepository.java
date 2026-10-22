package com.younglings.bot.commandusage;

import com.younglings.bot.commandusage.CommandUsageCounter.Key;
import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** How often each slash command was used, per server and UTC day (T-191). Counts only: no user ids. Rows older than {@link #KEEP_DAYS} are pruned. */
@BService
public class CommandUsageRepository {
    private static final Logger log = LoggerFactory.getLogger(CommandUsageRepository.class);

    /** How long daily counts are kept. */
    public static final int KEEP_DAYS = 90;

    private final ConnectionSupplier connectionSupplier;

    public CommandUsageRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "command usage", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.command_usage (
                    guild_id BIGINT NOT NULL,
                    command TEXT NOT NULL,
                    day DATE NOT NULL,
                    uses INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (guild_id, command, day)
                );
                """,
                "CREATE INDEX IF NOT EXISTS command_usage_day ON younglings.command_usage (day);"));
    }

    /** Adds a batch of counts to the stored ones, in one transaction. */
    public void add(Map<Key, Integer> batch) throws SQLException {
        if (batch.isEmpty()) return;
        try (Connection c = connectionSupplier.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO younglings.command_usage (guild_id, command, day, uses) VALUES (?, ?, ?, ?)
                    ON CONFLICT (guild_id, command, day) DO UPDATE SET uses = younglings.command_usage.uses + EXCLUDED.uses
                    """)) {
                for (Map.Entry<Key, Integer> e : batch.entrySet()) {
                    s.setLong(1, e.getKey().guildId());
                    s.setString(2, e.getKey().command());
                    s.setDate(3, Date.valueOf(e.getKey().day()));
                    s.setInt(4, e.getValue());
                    s.addBatch();
                }
                s.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    /** Uses per command in one server from {@code since} (inclusive) to today, most used first. */
    public Map<String, Integer> totals(long guildId, LocalDate since) {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT command, SUM(uses) AS n FROM younglings.command_usage
                     WHERE guild_id = ? AND day >= ? GROUP BY command ORDER BY n DESC, command
                     """)) {
            s.setLong(1, guildId);
            s.setDate(2, Date.valueOf(since));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.put(rs.getString("command"), rs.getInt("n"));
            }
        } catch (SQLException e) {
            log.warn("Could not read command usage for {}", guildId, e);
        }
        return out;
    }

    /** Deletes daily counts older than {@code before}; returns how many rows went. */
    public int pruneBefore(LocalDate before) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.command_usage WHERE day < ?")) {
            s.setDate(1, Date.valueOf(before));
            return s.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not prune old command usage", e);
            return 0;
        }
    }
}
