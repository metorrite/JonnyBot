package com.runeclans.bot.afk;

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
import java.util.ArrayList;
import java.util.List;

@BService
public class AfkRepository {
    private static final Logger log = LoggerFactory.getLogger(AfkRepository.class);

    /** Someone who has said they're away: why, and since when. */
    public record Afk(long guildId, long userId, String reason, Instant since) {}

    private final ConnectionSupplier connectionSupplier;

    public AfkRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "afk", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.afk (
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    reason TEXT NOT NULL DEFAULT '',
                    since TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, user_id)
                );
                """));
    }

    public List<Afk> forGuild(long guildId) {
        List<Afk> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT user_id, reason, since FROM younglings.afk WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(new Afk(guildId, rs.getLong("user_id"), rs.getString("reason"), rs.getTimestamp("since").toInstant()));
            }
            return list;
        } catch (SQLException e) {
            log.error("Failed to read the AFK list of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the AFK list", e);
        }
    }

    public void set(Afk afk) {
        String sql = "INSERT INTO younglings.afk (guild_id, user_id, reason, since) VALUES (?, ?, ?, ?) ON CONFLICT (guild_id, user_id) DO UPDATE SET reason = EXCLUDED.reason, since = EXCLUDED.since";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, afk.guildId());
            s.setLong(2, afk.userId());
            s.setString(3, afk.reason());
            s.setTimestamp(4, Timestamp.from(afk.since()));
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save AFK for {}", afk.userId(), e);
            throw new RuntimeException("Failed to save AFK", e);
        }
    }

    public void clear(long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.afk WHERE guild_id = ? AND user_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to clear AFK for {}", userId, e);
        }
    }
}
