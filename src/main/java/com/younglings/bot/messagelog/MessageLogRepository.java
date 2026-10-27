package com.younglings.bot.messagelog;

import com.younglings.bot.cachebus.CacheBus;
import com.younglings.bot.cachebus.CacheInvalidator;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Message log settings (kept in memory per server) and the short-lived cache of message text that edits and deletes are compared with. */
@BService
public class MessageLogRepository implements CacheInvalidator {
    /** The {@code bot_cache} area of this class's caches (docs/SCALING-STATE.md M10). */
    static final String CACHE_AREA = "message-log";

    private static final Logger log = LoggerFactory.getLogger(MessageLogRepository.class);

    /** What a message said when it was last seen. */
    public record Cached(long messageId, long guildId, long channelId, long authorId, String content, String attachments) {}

    public static final int MAX_CONTENT = 4000;

    private final ConnectionSupplier connectionSupplier;
    private final Map<Long, MessageLogConfig> configs = new ConcurrentHashMap<>();

    public MessageLogRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "messagelog", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.message_log_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    retention_days INTEGER NOT NULL DEFAULT 7,
                    excluded_channels TEXT NOT NULL DEFAULT '',
                    edit_channel BIGINT NULL,
                    delete_channel BIGINT NULL,
                    voice_channel BIGINT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.message_log_cache (
                    message_id BIGINT PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    author_id BIGINT NOT NULL,
                    content TEXT NOT NULL,
                    attachments TEXT NOT NULL DEFAULT '',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS message_log_cache_age ON younglings.message_log_cache (created_at);",
                "CREATE INDEX IF NOT EXISTS message_log_cache_guild ON younglings.message_log_cache (guild_id);"));
    }

    public MessageLogConfig config(long guildId) {
        return configs.computeIfAbsent(guildId, this::loadConfig);
    }

    private MessageLogConfig loadConfig(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, retention_days, excluded_channels, edit_channel, delete_channel, voice_channel FROM younglings.message_log_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return MessageLogConfig.defaults(guildId);
                return new MessageLogConfig(guildId, rs.getBoolean(1), rs.getInt(2), StarboardConfig.readIds(rs.getString(3)), nullableLong(rs, 4), nullableLong(rs, 5), nullableLong(rs, 6));
            }
        } catch (SQLException e) {
            log.error("Failed to read the message log settings of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the message log settings", e);
        }
    }

    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    public void saveConfig(MessageLogConfig config) {
        String sql = """
                INSERT INTO younglings.message_log_config (guild_id, enabled, retention_days, excluded_channels, edit_channel, delete_channel, voice_channel, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, retention_days = EXCLUDED.retention_days, excluded_channels = EXCLUDED.excluded_channels,
                    edit_channel = EXCLUDED.edit_channel, delete_channel = EXCLUDED.delete_channel, voice_channel = EXCLUDED.voice_channel, updated_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, config.guildId());
            s.setBoolean(2, config.enabled());
            s.setInt(3, config.retentionDays());
            s.setString(4, StarboardConfig.writeIds(config.excludedChannels()));
            setNullable(s, 5, config.editChannelId());
            setNullable(s, 6, config.deleteChannelId());
            setNullable(s, 7, config.voiceChannelId());
            s.executeUpdate();
            configs.remove(config.guildId());
            CacheBus.changed(CACHE_AREA, config.guildId());
        } catch (SQLException e) {
            log.error("Failed to save the message log settings of guild {}", config.guildId(), e);
            throw new RuntimeException("Failed to save the message log settings", e);
        }
    }

    private static void setNullable(PreparedStatement s, int index, Long value) throws SQLException {
        if (value == null) s.setNull(index, Types.BIGINT); else s.setLong(index, value);
    }

    // ---------- the message text cache ----------

    public void put(Cached message) {
        String sql = """
                INSERT INTO younglings.message_log_cache (message_id, guild_id, channel_id, author_id, content, attachments) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (message_id) DO UPDATE SET content = EXCLUDED.content, attachments = EXCLUDED.attachments
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, message.messageId());
            s.setLong(2, message.guildId());
            s.setLong(3, message.channelId());
            s.setLong(4, message.authorId());
            s.setString(5, clip(message.content()));
            s.setString(6, message.attachments());
            s.executeUpdate();
        } catch (SQLException e) {
            log.warn("Couldn't cache a message for the message log: {}", e.getMessage());
        }
    }

    static String clip(String content) {
        return content.length() <= MAX_CONTENT ? content : content.substring(0, MAX_CONTENT);
    }

    public Optional<Cached> get(long messageId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT guild_id, channel_id, author_id, content, attachments FROM younglings.message_log_cache WHERE message_id = ?")) {
            s.setLong(1, messageId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(new Cached(messageId, rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5))) : Optional.empty();
            }
        } catch (SQLException e) {
            log.warn("Couldn't read a cached message: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** The cached messages with these ids, then removes them. */
    public List<Cached> take(Collection<Long> messageIds) {
        List<Cached> found = new ArrayList<>();
        for (long id : messageIds) {
            get(id).ifPresent(found::add);
        }
        remove(messageIds);
        return found;
    }

    public void remove(Collection<Long> messageIds) {
        if (messageIds.isEmpty()) return;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.message_log_cache WHERE message_id = ?")) {
            for (long id : messageIds) {
                s.setLong(1, id);
                s.addBatch();
            }
            s.executeBatch();
        } catch (SQLException e) {
            log.warn("Couldn't remove cached messages: {}", e.getMessage());
        }
    }

    public long countFor(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.message_log_cache WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Forgets everything cached for a server (the dashboard's "clear now" and a server switching the feature off). */
    public int clearGuild(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.message_log_cache WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            return s.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to clear the cached messages of guild {}", guildId, e);
            return 0;
        }
    }

    /** Forgets everything cached in these channels (they were added to the excluded list). */
    public int clearChannels(long guildId, Collection<Long> channelIds) {
        int total = 0;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.message_log_cache WHERE guild_id = ? AND channel_id = ?")) {
            for (long channel : channelIds) {
                s.setLong(1, guildId);
                s.setLong(2, channel);
                total += s.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("Failed to clear cached messages of guild {}", guildId, e);
        }
        return total;
    }

    /**
     * The purge: removes cached text older than each server's retention (never more than 30 days whatever the setting), and everything cached for a
     * server that has the feature off or no settings at all. Returns how many rows went.
     */
    public int purge() {
        String sql = """
                DELETE FROM younglings.message_log_cache c
                WHERE c.created_at < NOW() - INTERVAL '30 days'
                   OR NOT EXISTS (SELECT 1 FROM younglings.message_log_config g WHERE g.guild_id = c.guild_id AND g.enabled)
                   OR c.created_at < NOW() - (SELECT g.retention_days FROM younglings.message_log_config g WHERE g.guild_id = c.guild_id) * INTERVAL '1 day'
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            return s.executeUpdate();
        } catch (SQLException e) {
            log.error("The message log purge failed", e);
            return 0;
        }
    }

    // ---------- cross-process invalidation (CacheBus) ----------

    @Override
    public String cacheArea() {
        return CACHE_AREA;
    }

    @Override
    public void evictCached(long guildId) {
        configs.remove(guildId);
    }

    @Override
    public void evictAllCached() {
        configs.clear();
    }
}
