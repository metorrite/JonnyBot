package com.younglings.bot.chatlevel;

import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.starboard.StarboardConfig;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Chat level settings, level roles and every member's XP. Own tables: nothing here touches the points ledger. */
@BService
public class ChatLevelRepository {
    private static final Logger log = LoggerFactory.getLogger(ChatLevelRepository.class);

    /** A member's standing: total XP and how many XP-earning messages they sent. */
    public record Standing(long userId, long xp, long messages) {}

    private final ConnectionSupplier connectionSupplier;
    private final Map<Long, ChatLevelConfig> configs = new ConcurrentHashMap<>();
    private final Map<Long, Map<Integer, Long>> roles = new ConcurrentHashMap<>();

    public ChatLevelRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "chatlevel", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.chat_level_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    min_xp INTEGER NOT NULL DEFAULT 15,
                    max_xp INTEGER NOT NULL DEFAULT 25,
                    cooldown_seconds INTEGER NOT NULL DEFAULT 60,
                    announce BOOLEAN NOT NULL DEFAULT FALSE,
                    announce_channel BIGINT NULL,
                    announce_message TEXT NOT NULL DEFAULT '{user} reached level {level}!',
                    remove_old_roles BOOLEAN NOT NULL DEFAULT FALSE,
                    ignored_channels TEXT NOT NULL DEFAULT '',
                    ignored_roles TEXT NOT NULL DEFAULT '',
                    channel_multipliers TEXT NOT NULL DEFAULT '',
                    role_multipliers TEXT NOT NULL DEFAULT '',
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.chat_level_member (
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    xp BIGINT NOT NULL DEFAULT 0,
                    messages BIGINT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (guild_id, user_id)
                );
                """,
                "CREATE INDEX IF NOT EXISTS chat_level_member_rank ON younglings.chat_level_member (guild_id, xp DESC);",
                """
                CREATE TABLE IF NOT EXISTS younglings.chat_level_role (
                    guild_id BIGINT NOT NULL,
                    level INTEGER NOT NULL,
                    role_id BIGINT NOT NULL,
                    PRIMARY KEY (guild_id, level)
                );
                """));
    }

    // ---------- settings ----------

    public ChatLevelConfig config(long guildId) {
        return configs.computeIfAbsent(guildId, this::loadConfig);
    }

    private ChatLevelConfig loadConfig(long guildId) {
        String sql = "SELECT enabled, min_xp, max_xp, cooldown_seconds, announce, announce_channel, announce_message, remove_old_roles, ignored_channels, ignored_roles, channel_multipliers, role_multipliers FROM younglings.chat_level_config WHERE guild_id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return ChatLevelConfig.defaults(guildId);
                long channel = rs.getLong(6);
                boolean none = rs.wasNull();
                return new ChatLevelConfig(guildId, rs.getBoolean(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getBoolean(5), none ? null : channel, rs.getString(7), rs.getBoolean(8),
                        StarboardConfig.readIds(rs.getString(9)), StarboardConfig.readIds(rs.getString(10)), ChatLevelConfig.readMultipliers(rs.getString(11)), ChatLevelConfig.readMultipliers(rs.getString(12)));
            }
        } catch (SQLException e) {
            log.error("Failed to read the chat level settings of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the chat level settings", e);
        }
    }

    public void saveConfig(ChatLevelConfig config) {
        String sql = """
                INSERT INTO younglings.chat_level_config (guild_id, enabled, min_xp, max_xp, cooldown_seconds, announce, announce_channel, announce_message, remove_old_roles,
                    ignored_channels, ignored_roles, channel_multipliers, role_multipliers, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, min_xp = EXCLUDED.min_xp, max_xp = EXCLUDED.max_xp, cooldown_seconds = EXCLUDED.cooldown_seconds,
                    announce = EXCLUDED.announce, announce_channel = EXCLUDED.announce_channel, announce_message = EXCLUDED.announce_message, remove_old_roles = EXCLUDED.remove_old_roles,
                    ignored_channels = EXCLUDED.ignored_channels, ignored_roles = EXCLUDED.ignored_roles, channel_multipliers = EXCLUDED.channel_multipliers,
                    role_multipliers = EXCLUDED.role_multipliers, updated_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, config.guildId());
            s.setBoolean(2, config.enabled());
            s.setInt(3, config.minXp());
            s.setInt(4, config.maxXp());
            s.setInt(5, config.cooldownSeconds());
            s.setBoolean(6, config.announce());
            if (config.announceChannelId() == null) s.setNull(7, Types.BIGINT); else s.setLong(7, config.announceChannelId());
            s.setString(8, config.announceMessage());
            s.setBoolean(9, config.removeOldRoles());
            s.setString(10, StarboardConfig.writeIds(config.ignoredChannels()));
            s.setString(11, StarboardConfig.writeIds(config.ignoredRoles()));
            s.setString(12, ChatLevelConfig.writeMultipliers(config.channelMultipliers()));
            s.setString(13, ChatLevelConfig.writeMultipliers(config.roleMultipliers()));
            s.executeUpdate();
            configs.remove(config.guildId());
        } catch (SQLException e) {
            log.error("Failed to save the chat level settings of guild {}", config.guildId(), e);
            throw new RuntimeException("Failed to save the chat level settings", e);
        }
    }

    // ---------- level roles ----------

    /** Level to role id, in level order. */
    public Map<Integer, Long> roles(long guildId) {
        return roles.computeIfAbsent(guildId, this::loadRoles);
    }

    private Map<Integer, Long> loadRoles(long guildId) {
        Map<Integer, Long> map = new TreeMap<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT level, role_id FROM younglings.chat_level_role WHERE guild_id = ? ORDER BY level")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) map.put(rs.getInt(1), rs.getLong(2));
            }
            return java.util.Collections.unmodifiableMap(map);
        } catch (SQLException e) {
            log.error("Failed to read the level roles of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the level roles", e);
        }
    }

    /** Replaces the whole list of level roles. */
    public void saveRoles(long guildId, Map<Integer, Long> newRoles) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM younglings.chat_level_role WHERE guild_id = ?");
                 PreparedStatement insert = c.prepareStatement("INSERT INTO younglings.chat_level_role (guild_id, level, role_id) VALUES (?, ?, ?)")) {
                delete.setLong(1, guildId);
                delete.executeUpdate();
                for (Map.Entry<Integer, Long> e : newRoles.entrySet()) {
                    insert.setLong(1, guildId);
                    insert.setInt(2, e.getKey());
                    insert.setLong(3, e.getValue());
                    insert.addBatch();
                }
                insert.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
            roles.remove(guildId);
        } catch (SQLException e) {
            log.error("Failed to save the level roles of guild {}", guildId, e);
            throw new RuntimeException("Failed to save the level roles", e);
        }
    }

    // ---------- XP ----------

    /** Adds XP for one message and returns the member's total afterwards. */
    public long addXp(long guildId, long userId, int amount) {
        String sql = """
                INSERT INTO younglings.chat_level_member (guild_id, user_id, xp, messages) VALUES (?, ?, ?, 1)
                ON CONFLICT (guild_id, user_id) DO UPDATE SET xp = younglings.chat_level_member.xp + EXCLUDED.xp, messages = younglings.chat_level_member.messages + 1, updated_at = NOW()
                RETURNING xp
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setLong(3, amount);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.warn("Couldn't save chat XP in guild {}: {}", guildId, e.getMessage());
            return -1;
        }
    }

    public Optional<Standing> standing(long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT xp, messages FROM younglings.chat_level_member WHERE guild_id = ? AND user_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(new Standing(userId, rs.getLong(1), rs.getLong(2))) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to read chat XP in guild {}", guildId, e);
            throw new RuntimeException("Failed to read chat XP", e);
        }
    }

    /** 1 for the member with the most XP; the members with more XP than {@code xp}, plus one. */
    public long rankOf(long guildId, long xp) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.chat_level_member WHERE guild_id = ? AND xp > ?")) {
            s.setLong(1, guildId);
            s.setLong(2, xp);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1) + 1;
            }
        } catch (SQLException e) {
            log.error("Failed to rank chat XP in guild {}", guildId, e);
            throw new RuntimeException("Failed to rank chat XP", e);
        }
    }

    public List<Standing> top(long guildId, int limit) {
        List<Standing> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT user_id, xp, messages FROM younglings.chat_level_member WHERE guild_id = ? ORDER BY xp DESC, user_id LIMIT ?")) {
            s.setLong(1, guildId);
            s.setInt(2, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(new Standing(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
            }
            return list;
        } catch (SQLException e) {
            log.error("Failed to read the chat level leaderboard of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the leaderboard", e);
        }
    }

    public long countMembers(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.chat_level_member WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Sets one member back to nothing (they stay unranked until they chat again). */
    public boolean reset(long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.chat_level_member WHERE guild_id = ? AND user_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to reset a member's chat XP in guild {}", guildId, e);
            throw new RuntimeException("Failed to reset the member", e);
        }
    }

    public int resetAll(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.chat_level_member WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            return s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to reset chat XP in guild {}", guildId, e);
            throw new RuntimeException("Failed to reset chat XP", e);
        }
    }
}
