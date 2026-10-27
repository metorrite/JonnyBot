package com.younglings.bot.starboard;

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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@BService
public class StarboardRepository {
    private static final Logger log = LoggerFactory.getLogger(StarboardRepository.class);

    /** A message that is on the board: where it came from and the copy that was posted. */
    public record Post(long guildId, long sourceChannelId, long sourceMessageId, long boardMessageId, int stars) {}

    private final ConnectionSupplier connectionSupplier;
    /**
     * Each server's config as last read, since every reaction in every server asks for it. Filled through computeIfAbsent and dropped
     * after every write, so a read that races a save can't leave the old config behind (the drop waits for the read to finish).
     */
    private final ConcurrentHashMap<Long, StarboardConfig> cache = new ConcurrentHashMap<>();

    public StarboardRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "starboard", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.starboard_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    channel_id BIGINT NULL,
                    emoji TEXT NOT NULL DEFAULT '⭐',
                    threshold INTEGER NOT NULL DEFAULT 3,
                    self_star BOOLEAN NOT NULL DEFAULT FALSE,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.starboard_post (
                    guild_id BIGINT NOT NULL,
                    source_channel_id BIGINT NOT NULL,
                    source_message_id BIGINT NOT NULL,
                    board_message_id BIGINT NOT NULL,
                    stars INTEGER NOT NULL,
                    PRIMARY KEY (guild_id, source_message_id)
                );
                """,
                "ALTER TABLE younglings.starboard_config ADD COLUMN IF NOT EXISTS channel_thresholds TEXT NOT NULL DEFAULT '';",
                "ALTER TABLE younglings.starboard_config ADD COLUMN IF NOT EXISTS skip_hidden BOOLEAN NOT NULL DEFAULT FALSE;",
                "ALTER TABLE younglings.starboard_config ADD COLUMN IF NOT EXISTS only_channels TEXT NOT NULL DEFAULT '';"));
    }

    public StarboardConfig get(long guildId) {
        return cache.computeIfAbsent(guildId, this::load);
    }

    /** Forgets a server's cached config, for when its rows are deleted outside this class (the retention purge). */
    public void evict(long guildId) {
        cache.remove(guildId);
    }

    private StarboardConfig load(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, channel_id, emoji, threshold, self_star, channel_thresholds, skip_hidden, only_channels FROM younglings.starboard_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return StarboardConfig.defaults(guildId);
                long channel = rs.getLong("channel_id");
                boolean none = rs.wasNull();
                return new StarboardConfig(guildId, rs.getBoolean("enabled"), none ? null : channel, rs.getString("emoji"), rs.getInt("threshold"), rs.getBoolean("self_star"),
                        StarboardConfig.readThresholds(rs.getString("channel_thresholds")), rs.getBoolean("skip_hidden"), StarboardConfig.readIds(rs.getString("only_channels")));
            }
        } catch (SQLException e) {
            log.error("Failed to read the starboard of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the starboard", e);
        }
    }

    public void save(StarboardConfig config) {
        String sql = """
                INSERT INTO younglings.starboard_config (guild_id, enabled, channel_id, emoji, threshold, self_star, channel_thresholds, skip_hidden, only_channels, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, channel_id = EXCLUDED.channel_id, emoji = EXCLUDED.emoji,
                    threshold = EXCLUDED.threshold, self_star = EXCLUDED.self_star, channel_thresholds = EXCLUDED.channel_thresholds,
                    skip_hidden = EXCLUDED.skip_hidden, only_channels = EXCLUDED.only_channels, updated_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, config.guildId());
            s.setBoolean(2, config.enabled());
            if (config.channelId() == null) s.setNull(3, Types.BIGINT); else s.setLong(3, config.channelId());
            s.setString(4, config.emoji());
            s.setInt(5, config.threshold());
            s.setBoolean(6, config.selfStar());
            s.setString(7, StarboardConfig.writeThresholds(config.channelThresholds()));
            s.setBoolean(8, config.skipHiddenChannels());
            s.setString(9, StarboardConfig.writeIds(config.onlyChannels()));
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save the starboard of guild {}", config.guildId(), e);
            throw new RuntimeException("Failed to save the starboard", e);
        } finally {
            cache.remove(config.guildId());
        }
    }

    public Optional<Post> post(long guildId, long sourceMessageId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT source_channel_id, board_message_id, stars FROM younglings.starboard_post WHERE guild_id = ? AND source_message_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, sourceMessageId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(new Post(guildId, rs.getLong(1), sourceMessageId, rs.getLong(2), rs.getInt(3))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read a starboard post", e);
        }
    }

    public void savePost(Post post) {
        String sql = """
                INSERT INTO younglings.starboard_post (guild_id, source_channel_id, source_message_id, board_message_id, stars) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (guild_id, source_message_id) DO UPDATE SET board_message_id = EXCLUDED.board_message_id, stars = EXCLUDED.stars
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, post.guildId());
            s.setLong(2, post.sourceChannelId());
            s.setLong(3, post.sourceMessageId());
            s.setLong(4, post.boardMessageId());
            s.setInt(5, post.stars());
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save a starboard post", e);
        }
    }

    public void deletePost(long guildId, long sourceMessageId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.starboard_post WHERE guild_id = ? AND source_message_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, sourceMessageId);
            s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete a starboard post", e);
        }
    }
}
