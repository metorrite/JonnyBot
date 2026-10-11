package com.younglings.bot.points;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** A server's rewards (things members can spend points on) and the redemptions made against them. */
@BService
public class RewardsRepository {
    private static final Logger log = LoggerFactory.getLogger(RewardsRepository.class);

    /** {@code stock} is null for unlimited. An archived reward ({@code active} false) is hidden but its history is kept. */
    public record Reward(long id, long guildId, String name, String description, long cost, Long stock, boolean active) {}

    public record Redemption(long id, long guildId, long rewardId, String rewardName, long userId, long cost, PointsRules.Fulfilment status,
                             Long handledBy, Instant createdAt, Instant handledAt) {}

    private final ConnectionSupplier connectionSupplier;

    public RewardsRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "points rewards", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.points_reward (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    cost BIGINT NOT NULL,
                    stock BIGINT NULL,
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS points_reward_guild ON younglings.points_reward (guild_id, active, cost);",
                """
                CREATE TABLE IF NOT EXISTS younglings.points_redemption (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    reward_id BIGINT NOT NULL,
                    reward_name TEXT NOT NULL,
                    user_id BIGINT NOT NULL,
                    cost BIGINT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    handled_by BIGINT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    handled_at TIMESTAMPTZ NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS points_redemption_guild ON younglings.points_redemption (guild_id, status, id DESC);"));
    }

    // ---------- rewards ----------

    public long addReward(long guildId, String name, String description, long cost, Long stock) {
        String sql = "INSERT INTO younglings.points_reward (guild_id, name, description, cost, stock) VALUES (?, ?, ?, ?, ?) RETURNING id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setString(2, name);
            s.setString(3, description);
            s.setLong(4, cost);
            if (stock == null) s.setNull(5, Types.BIGINT); else s.setLong(5, stock);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add the reward", e);
        }
    }

    public boolean updateReward(long guildId, long id, String name, String description, long cost, Long stock, boolean active) {
        String sql = "UPDATE younglings.points_reward SET name = ?, description = ?, cost = ?, stock = ?, active = ? WHERE guild_id = ? AND id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, name);
            s.setString(2, description);
            s.setLong(3, cost);
            if (stock == null) s.setNull(4, Types.BIGINT); else s.setLong(4, stock);
            s.setBoolean(5, active);
            s.setLong(6, guildId);
            s.setLong(7, id);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update the reward", e);
        }
    }

    public Optional<Reward> reward(long guildId, long id) {
        return rewards("SELECT * FROM younglings.points_reward WHERE guild_id = ? AND id = ?", guildId, id).stream().findFirst();
    }

    /** The server's rewards, cheapest first. {@code activeOnly} hides the archived ones. */
    public List<Reward> rewards(long guildId, boolean activeOnly) {
        return rewards("SELECT * FROM younglings.points_reward WHERE guild_id = ? " + (activeOnly ? "AND active " : "") + "ORDER BY active DESC, cost, id", guildId, null);
    }

    public int activeCount(long guildId) {
        return (int) rewards(guildId, true).size();
    }

    /** Takes one from the stock when there is any. Empty when the reward is archived or sold out; returns the cost otherwise. */
    public OptionalLong claimStock(long guildId, long rewardId) {
        String sql = "UPDATE younglings.points_reward SET stock = CASE WHEN stock IS NULL THEN NULL ELSE stock - 1 END WHERE guild_id = ? AND id = ? AND active AND (stock IS NULL OR stock > 0) RETURNING cost";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, rewardId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to claim the reward", e);
        }
    }

    public void returnStock(long guildId, long rewardId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.points_reward SET stock = stock + 1 WHERE guild_id = ? AND id = ? AND stock IS NOT NULL")) {
            s.setLong(1, guildId);
            s.setLong(2, rewardId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to return the stock", e);
        }
    }

    // ---------- redemptions ----------

    public long addRedemption(long guildId, Reward reward, long userId, long cost) {
        String sql = "INSERT INTO younglings.points_redemption (guild_id, reward_id, reward_name, user_id, cost) VALUES (?, ?, ?, ?, ?) RETURNING id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, reward.id());
            s.setString(3, reward.name());
            s.setLong(4, userId);
            s.setLong(5, cost);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the redemption", e);
        }
    }

    public void deleteRedemption(long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.points_redemption WHERE id = ? AND status = 'PENDING'")) {
            s.setLong(1, id);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove the redemption", e);
        }
    }

    public Optional<Redemption> redemption(long guildId, long id) {
        return redemptions("SELECT * FROM younglings.points_redemption WHERE guild_id = ? AND id = ?", guildId, id, null).stream().findFirst();
    }

    public List<Redemption> pendingRedemptions(long guildId, int limit) {
        return redemptions("SELECT * FROM younglings.points_redemption WHERE guild_id = ? AND status = 'PENDING' ORDER BY id LIMIT ?", guildId, null, limit);
    }

    public List<Redemption> handledRedemptions(long guildId, int limit) {
        return redemptions("SELECT * FROM younglings.points_redemption WHERE guild_id = ? AND status <> 'PENDING' ORDER BY handled_at DESC, id DESC LIMIT ?", guildId, null, limit);
    }

    /** Moves a pending redemption to fulfilled or cancelled. False when it was already handled, so only one click wins. */
    public boolean handle(long guildId, long id, PointsRules.Fulfilment status, long handledBy) {
        String sql = "UPDATE younglings.points_redemption SET status = ?, handled_by = ?, handled_at = NOW() WHERE guild_id = ? AND id = ? AND status = 'PENDING'";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, status.name());
            s.setLong(2, handledBy);
            s.setLong(3, guildId);
            s.setLong(4, id);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to handle the redemption", e);
        }
    }

    private List<Reward> rewards(String sql, long guildId, Long id) {
        List<Reward> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            if (id != null) s.setLong(2, id);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    long stock = rs.getLong("stock");
                    boolean unlimited = rs.wasNull();
                    list.add(new Reward(rs.getLong("id"), rs.getLong("guild_id"), rs.getString("name"), rs.getString("description"), rs.getLong("cost"),
                            unlimited ? null : stock, rs.getBoolean("active")));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the rewards", e);
        }
    }

    private List<Redemption> redemptions(String sql, long guildId, Long id, Integer limit) {
        List<Redemption> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            int i = 1;
            s.setLong(i++, guildId);
            if (id != null) s.setLong(i++, id);
            if (limit != null) s.setInt(i, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    long by = rs.getLong("handled_by");
                    boolean noBy = rs.wasNull();
                    list.add(new Redemption(rs.getLong("id"), rs.getLong("guild_id"), rs.getLong("reward_id"), rs.getString("reward_name"), rs.getLong("user_id"),
                            rs.getLong("cost"), PointsRules.Fulfilment.valueOf(rs.getString("status")), noBy ? null : by,
                            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("handled_at") == null ? null : rs.getTimestamp("handled_at").toInstant()));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the redemptions", e);
        }
    }
}
