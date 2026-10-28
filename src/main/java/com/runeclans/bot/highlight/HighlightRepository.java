package com.runeclans.bot.highlight;

import com.runeclans.bot.cachebus.CacheBus;
import com.runeclans.bot.cachebus.CacheInvalidator;
import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.starboard.StarboardConfig;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Highlights settings, members' words and the channels members ignore. Every message in a server asks for these, so they are kept in memory per server and refreshed whenever they change. */
@BService
public class HighlightRepository implements CacheInvalidator {
    /** The {@code bot_cache} area of this class's caches (docs/SCALING-STATE.md M10). */
    static final String CACHE_AREA = "highlight";

    private static final Logger log = LoggerFactory.getLogger(HighlightRepository.class);

    /** One word a member watches for. */
    public record Keyword(long id, long guildId, long userId, String word, boolean wholeWord) {}

    private final ConnectionSupplier connectionSupplier;
    private final Map<Long, HighlightConfig> configs = new ConcurrentHashMap<>();
    private final Map<Long, List<Keyword>> keywords = new ConcurrentHashMap<>();
    private final Map<Long, Map<Long, Set<Long>>> ignores = new ConcurrentHashMap<>();

    public HighlightRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "highlight", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.highlight_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    max_keywords INTEGER NOT NULL DEFAULT 10,
                    cooldown_seconds INTEGER NOT NULL DEFAULT 300,
                    excluded_channels TEXT NOT NULL DEFAULT '',
                    allowed_roles TEXT NOT NULL DEFAULT '',
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.highlight_keyword (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    word TEXT NOT NULL,
                    whole_word BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS highlight_keyword_unique ON younglings.highlight_keyword (guild_id, user_id, lower(word));",
                """
                CREATE TABLE IF NOT EXISTS younglings.highlight_ignore (
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    PRIMARY KEY (guild_id, user_id, channel_id)
                );
                """));
    }

    // ---------- server settings ----------

    public HighlightConfig config(long guildId) {
        return configs.computeIfAbsent(guildId, this::loadConfig);
    }

    private HighlightConfig loadConfig(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, max_keywords, cooldown_seconds, excluded_channels, allowed_roles FROM younglings.highlight_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return HighlightConfig.defaults(guildId);
                return new HighlightConfig(guildId, rs.getBoolean(1), rs.getInt(2), rs.getInt(3), StarboardConfig.readIds(rs.getString(4)), StarboardConfig.readIds(rs.getString(5)));
            }
        } catch (SQLException e) {
            log.error("Failed to read the Highlights settings of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the Highlights settings", e);
        }
    }

    public void saveConfig(HighlightConfig config) {
        String sql = """
                INSERT INTO younglings.highlight_config (guild_id, enabled, max_keywords, cooldown_seconds, excluded_channels, allowed_roles, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, max_keywords = EXCLUDED.max_keywords, cooldown_seconds = EXCLUDED.cooldown_seconds,
                    excluded_channels = EXCLUDED.excluded_channels, allowed_roles = EXCLUDED.allowed_roles, updated_at = NOW()
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, config.guildId());
            s.setBoolean(2, config.enabled());
            s.setInt(3, config.maxKeywords());
            s.setInt(4, config.cooldownSeconds());
            s.setString(5, StarboardConfig.writeIds(config.excludedChannels()));
            s.setString(6, StarboardConfig.writeIds(config.allowedRoles()));
            s.executeUpdate();
            configs.remove(config.guildId());
            CacheBus.changed(CACHE_AREA, config.guildId());
        } catch (SQLException e) {
            log.error("Failed to save the Highlights settings of guild {}", config.guildId(), e);
            throw new RuntimeException("Failed to save the Highlights settings", e);
        }
    }

    // ---------- words ----------

    /** Every member's words in this server (from memory after the first look). */
    public List<Keyword> keywords(long guildId) {
        return keywords.computeIfAbsent(guildId, this::loadKeywords);
    }

    private List<Keyword> loadKeywords(long guildId) {
        List<Keyword> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT id, user_id, word, whole_word FROM younglings.highlight_keyword WHERE guild_id = ? ORDER BY id")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(new Keyword(rs.getLong(1), guildId, rs.getLong(2), rs.getString(3), rs.getBoolean(4)));
            }
            return List.copyOf(list);
        } catch (SQLException e) {
            log.error("Failed to read the Highlights words of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the Highlights words", e);
        }
    }

    public List<Keyword> keywordsOf(long guildId, long userId) {
        return keywords(guildId).stream().filter(k -> k.userId() == userId).toList();
    }

    /** Adds the word; false when the member already watches it. */
    public boolean addKeyword(long guildId, long userId, String word, boolean wholeWord) {
        String sql = "INSERT INTO younglings.highlight_keyword (guild_id, user_id, word, whole_word) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setString(3, word);
            s.setBoolean(4, wholeWord);
            boolean added = s.executeUpdate() > 0;
            keywords.remove(guildId);
            CacheBus.changed(CACHE_AREA, guildId);
            return added;
        } catch (SQLException e) {
            log.error("Failed to add a Highlights word in guild {}", guildId, e);
            throw new RuntimeException("Failed to add the word", e);
        }
    }

    /** Removes the member's word (matched by id, or by its text ignoring case); false when they had none like that. */
    public boolean removeKeyword(long guildId, long userId, Long id, String word) {
        String sql = id != null
                ? "DELETE FROM younglings.highlight_keyword WHERE guild_id = ? AND user_id = ? AND id = ?"
                : "DELETE FROM younglings.highlight_keyword WHERE guild_id = ? AND user_id = ? AND lower(word) = lower(?)";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            if (id != null) s.setLong(3, id); else s.setString(3, word);
            boolean gone = s.executeUpdate() > 0;
            keywords.remove(guildId);
            CacheBus.changed(CACHE_AREA, guildId);
            return gone;
        } catch (SQLException e) {
            log.error("Failed to remove a Highlights word in guild {}", guildId, e);
            throw new RuntimeException("Failed to remove the word", e);
        }
    }

    // ---------- channels a member ignores ----------

    public Set<Long> ignoredBy(long guildId, long userId) {
        return ignores.computeIfAbsent(guildId, this::loadIgnores).getOrDefault(userId, Set.of());
    }

    private Map<Long, Set<Long>> loadIgnores(long guildId) {
        Map<Long, Set<Long>> map = new ConcurrentHashMap<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT user_id, channel_id FROM younglings.highlight_ignore WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) map.computeIfAbsent(rs.getLong(1), k -> new LinkedHashSet<>()).add(rs.getLong(2));
            }
            return map;
        } catch (SQLException e) {
            log.error("Failed to read the Highlights ignored channels of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the ignored channels", e);
        }
    }

    public void setIgnored(long guildId, long userId, long channelId, boolean ignored) {
        String sql = ignored
                ? "INSERT INTO younglings.highlight_ignore (guild_id, user_id, channel_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
                : "DELETE FROM younglings.highlight_ignore WHERE guild_id = ? AND user_id = ? AND channel_id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setLong(3, channelId);
            s.executeUpdate();
            ignores.remove(guildId);
            CacheBus.changed(CACHE_AREA, guildId);
        } catch (SQLException e) {
            log.error("Failed to change a Highlights ignored channel in guild {}", guildId, e);
            throw new RuntimeException("Failed to change the ignored channel", e);
        }
    }

    /** Forgets everything a member set (they left the server, or asked). */
    public void forgetMember(long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection()) {
            for (String table : List.of("highlight_keyword", "highlight_ignore")) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ? AND user_id = ?")) {
                    s.setLong(1, guildId);
                    s.setLong(2, userId);
                    s.executeUpdate();
                }
            }
            keywords.remove(guildId);
            ignores.remove(guildId);
            CacheBus.changed(CACHE_AREA, guildId);
        } catch (SQLException e) {
            log.error("Failed to clear a member's Highlights in guild {}", guildId, e);
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
        keywords.remove(guildId);
        ignores.remove(guildId);
    }

    @Override
    public void evictAllCached() {
        configs.clear();
        keywords.clear();
        ignores.clear();
    }
}
