package com.runeclans.bot.leagues;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Each server's Discord side of alliance leagues (T-760, RuneClan docs/ALLIANCE-LEAGUE.md 10): the channel its league posts go to. No row,
 * or a row without a channel, means no posts; the dashboard's Leagues page picks it.
 */
@BService
public class LeagueDiscordStore {
    private static final Logger log = LoggerFactory.getLogger(LeagueDiscordStore.class);

    private final ConnectionSupplier connectionSupplier;

    public LeagueDiscordStore(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "league discord", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.league_discord (
                    guild_id BIGINT PRIMARY KEY,
                    channel_id BIGINT NULL,
                    updated_by BIGINT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """));
    }

    /** The channel this server's league posts go to, if one is picked. */
    public Optional<Long> channel(long guildId) {
        String sql = "SELECT channel_id FROM younglings.league_discord WHERE guild_id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                long channel = rs.getLong(1);
                return rs.wasNull() ? Optional.empty() : Optional.of(channel);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Couldn't read the league channel of server " + guildId, e);
        }
    }

    /** Picks the channel (null for none) and remembers who did. */
    public void setChannel(long guildId, Long channelId, long actorId) {
        String sql = """
                INSERT INTO younglings.league_discord (guild_id, channel_id, updated_by, updated_at) VALUES (?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET channel_id = EXCLUDED.channel_id, updated_by = EXCLUDED.updated_by, updated_at = NOW()""";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, guildId);
            if (channelId == null) ps.setNull(2, Types.BIGINT);
            else ps.setLong(2, channelId);
            ps.setLong(3, actorId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Couldn't save the league channel of server " + guildId, e);
        }
    }

    /** Every server with a channel picked: server to channel, in server order. */
    public Map<Long, Long> channels() {
        Map<Long, Long> out = new LinkedHashMap<>();
        String sql = "SELECT guild_id, channel_id FROM younglings.league_discord WHERE channel_id IS NOT NULL ORDER BY guild_id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.put(rs.getLong(1), rs.getLong(2));
        } catch (SQLException e) {
            throw new IllegalStateException("Couldn't list the league channels", e);
        }
        return out;
    }
}
