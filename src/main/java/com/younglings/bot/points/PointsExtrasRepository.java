package com.younglings.bot.points;

import com.younglings.bot.cachebus.CacheBus;
import com.younglings.bot.cachebus.CacheInvalidator;
import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.points.PointsExtrasRules.RoleReward;
import com.younglings.bot.points.PointsExtrasRules.Trigger;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** Role rewards and keyword triggers of a server's points. Triggers are read for every message, so they are kept in memory and refreshed when they change. */
@BService
public class PointsExtrasRepository implements CacheInvalidator {
    /** The {@code bot_cache} area of this class's caches (docs/SCALING-STATE.md M10). */
    static final String CACHE_AREA = "points-extras";

    private static final Logger log = LoggerFactory.getLogger(PointsExtrasRepository.class);

    private final ConnectionSupplier connectionSupplier;
    private final Map<Long, List<Trigger>> triggerCache = new ConcurrentHashMap<>();

    public PointsExtrasRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "points extras", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.points_role_reward (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    threshold BIGINT NOT NULL,
                    role_id BIGINT NOT NULL,
                    remove_lower BOOLEAN NOT NULL DEFAULT FALSE,
                    UNIQUE (guild_id, threshold)
                );
                """,
                // which member has been given which reward: a reward is given once, so a role staff took away stays gone
                """
                CREATE TABLE IF NOT EXISTS younglings.points_role_grant (
                    reward_id BIGINT NOT NULL REFERENCES younglings.points_role_reward (id) ON DELETE CASCADE,
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (reward_id, user_id)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.points_keyword (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    keyword TEXT NOT NULL,
                    points INTEGER NOT NULL,
                    cooldown_seconds INTEGER NOT NULL DEFAULT 60,
                    daily_cap INTEGER NOT NULL DEFAULT 5,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    UNIQUE (guild_id, keyword)
                );
                """));
    }

    // ---------- role rewards ----------

    public List<RoleReward> roleRewards(long guildId) {
        List<RoleReward> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT id, guild_id, threshold, role_id, remove_lower FROM younglings.points_role_reward WHERE guild_id = ? ORDER BY threshold")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(new RoleReward(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getBoolean(5)));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the role rewards", e);
        }
    }

    /** Adds a reward (id 0) or changes one. Returns false when the threshold is already used by another reward of the server. */
    public boolean saveRoleReward(RoleReward reward) {
        try (Connection c = connectionSupplier.getConnection()) {
            if (reward.id() == 0) {
                try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.points_role_reward (guild_id, threshold, role_id, remove_lower) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
                    s.setLong(1, reward.guildId());
                    s.setLong(2, reward.threshold());
                    s.setLong(3, reward.roleId());
                    s.setBoolean(4, reward.removeLower());
                    return s.executeUpdate() > 0;
                }
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.points_role_reward SET threshold = ?, role_id = ?, remove_lower = ? WHERE id = ? AND guild_id = ?")) {
                s.setLong(1, reward.threshold());
                s.setLong(2, reward.roleId());
                s.setBoolean(3, reward.removeLower());
                s.setLong(4, reward.id());
                s.setLong(5, reward.guildId());
                return s.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the role reward", e);
        }
    }

    public boolean deleteRoleReward(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.points_role_reward WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the role reward", e);
        }
    }

    public Set<Long> grantedTo(long guildId, long userId) {
        Set<Long> out = new HashSet<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT reward_id FROM younglings.points_role_grant WHERE guild_id = ? AND user_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the given role rewards", e);
        }
    }

    /** Claims a reward for a member. Returns true only for the one call that claimed it, so it is given once even if two ledger writes race. */
    public boolean claimGrant(long guildId, long userId, long rewardId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO younglings.points_role_grant (reward_id, guild_id, user_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            s.setLong(1, rewardId);
            s.setLong(2, guildId);
            s.setLong(3, userId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the given role reward", e);
        }
    }

    /** Un-claims a reward, when giving the role failed, so it is tried again after the member's next ledger entry. */
    public void releaseGrant(long userId, long rewardId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.points_role_grant WHERE reward_id = ? AND user_id = ?")) {
            s.setLong(1, rewardId);
            s.setLong(2, userId);
            s.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not release the role reward {} for {}", rewardId, userId, e);
        }
    }

    // ---------- keyword triggers ----------

    public List<Trigger> triggers(long guildId) {
        return triggerCache.computeIfAbsent(guildId, this::loadTriggers);
    }

    public Optional<Trigger> trigger(long guildId, long id) {
        return loadTriggers(guildId).stream().filter(t -> t.id() == id).findFirst();
    }

    private List<Trigger> loadTriggers(long guildId) {
        List<Trigger> out = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT id, guild_id, keyword, points, cooldown_seconds, daily_cap, enabled FROM younglings.points_keyword WHERE guild_id = ? ORDER BY id")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(new Trigger(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getBoolean(7)));
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the keyword triggers", e);
        }
    }

    /** Adds a trigger (id 0) or changes one. Returns false when the keyword is already another trigger of the server. */
    public boolean saveTrigger(Trigger t) {
        try (Connection c = connectionSupplier.getConnection()) {
            boolean done;
            if (t.id() == 0) {
                try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.points_keyword (guild_id, keyword, points, cooldown_seconds, daily_cap, enabled) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
                    s.setLong(1, t.guildId());
                    s.setString(2, t.keyword());
                    s.setInt(3, t.points());
                    s.setInt(4, t.cooldownSeconds());
                    s.setInt(5, t.dailyCap());
                    s.setBoolean(6, t.enabled());
                    done = s.executeUpdate() > 0;
                }
            } else {
                try (PreparedStatement s = c.prepareStatement("UPDATE younglings.points_keyword SET keyword = ?, points = ?, cooldown_seconds = ?, daily_cap = ?, enabled = ? WHERE id = ? AND guild_id = ?")) {
                    s.setString(1, t.keyword());
                    s.setInt(2, t.points());
                    s.setInt(3, t.cooldownSeconds());
                    s.setInt(4, t.dailyCap());
                    s.setBoolean(5, t.enabled());
                    s.setLong(6, t.id());
                    s.setLong(7, t.guildId());
                    done = s.executeUpdate() > 0;
                }
            }
            triggerCache.remove(t.guildId());
            CacheBus.changed(CACHE_AREA, t.guildId());
            return done;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the keyword trigger", e);
        }
    }

    public boolean deleteTrigger(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.points_keyword WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            boolean done = s.executeUpdate() > 0;
            triggerCache.remove(guildId);
            CacheBus.changed(CACHE_AREA, guildId);
            return done;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the keyword trigger", e);
        }
    }

    /** How many ledger entries of a member match a source-key pattern (a trigger's payments to them today). */
    public int countPayments(long guildId, long userId, String likePattern) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT COUNT(*) FROM younglings.points_ledger WHERE guild_id = ? AND user_id = ? AND source_key LIKE ?")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setString(3, likePattern);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count the keyword payments", e);
        }
    }

    // ---------- cross-process invalidation (CacheBus) ----------

    @Override
    public String cacheArea() {
        return CACHE_AREA;
    }

    @Override
    public void evictCached(long guildId) {
        triggerCache.remove(guildId);
    }

    @Override
    public void evictAllCached() {
        triggerCache.clear();
    }
}
