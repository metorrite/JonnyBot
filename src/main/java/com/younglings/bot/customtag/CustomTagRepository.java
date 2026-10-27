package com.younglings.bot.customtag;

import com.younglings.bot.cachebus.CacheBus;
import com.younglings.bot.cachebus.CacheInvalidator;
import com.younglings.bot.customtag.CustomTag.Delivery;
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
import java.util.concurrent.ConcurrentHashMap;

/** A server's tags and whether it uses them at all. Tags are kept in memory per server (the {@code /tag} autocomplete asks for them on every keystroke) and refreshed whenever they change. */
@BService
public class CustomTagRepository implements CacheInvalidator {
    /** The {@code bot_cache} area of this class's caches (docs/SCALING-STATE.md M10). */
    static final String CACHE_AREA = "custom-tag";

    private static final Logger log = LoggerFactory.getLogger(CustomTagRepository.class);

    private final ConnectionSupplier connectionSupplier;
    private final Map<Long, List<CustomTag>> cache = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> enabled = new ConcurrentHashMap<>();

    public CustomTagRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "customtag", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.custom_tag_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.custom_tag (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    name TEXT NOT NULL,
                    content TEXT NOT NULL,
                    embed BOOLEAN NOT NULL DEFAULT FALSE,
                    embed_title TEXT NOT NULL DEFAULT '',
                    embed_color INTEGER NULL,
                    delivery TEXT NOT NULL DEFAULT 'CHANNEL',
                    allowed_roles TEXT NOT NULL DEFAULT '',
                    cooldown_seconds INTEGER NOT NULL DEFAULT 0,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    uses BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS custom_tag_name ON younglings.custom_tag (guild_id, name);"));
    }

    // ---------- the server's switch ----------

    public boolean enabled(long guildId) {
        return enabled.computeIfAbsent(guildId, id -> {
            try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT enabled FROM younglings.custom_tag_config WHERE guild_id = ?")) {
                s.setLong(1, id);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            } catch (SQLException e) {
                log.error("Failed to read the custom tag switch of guild {}", id, e);
                throw new RuntimeException("Failed to read the custom tag switch", e);
            }
        });
    }

    public void setEnabled(long guildId, boolean on) {
        String sql = "INSERT INTO younglings.custom_tag_config (guild_id, enabled, updated_at) VALUES (?, ?, NOW()) ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = NOW()";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setBoolean(2, on);
            s.executeUpdate();
            enabled.remove(guildId);
            CacheBus.changed(CACHE_AREA, guildId);
        } catch (SQLException e) {
            log.error("Failed to save the custom tag switch of guild {}", guildId, e);
            throw new RuntimeException("Failed to save the custom tag switch", e);
        }
    }

    // ---------- tags ----------

    public List<CustomTag> forGuild(long guildId) {
        return cache.computeIfAbsent(guildId, this::load);
    }

    private List<CustomTag> load(long guildId) {
        List<CustomTag> list = new ArrayList<>();
        String sql = "SELECT id, name, content, embed, embed_title, embed_color, delivery, allowed_roles, cooldown_seconds, enabled, uses FROM younglings.custom_tag WHERE guild_id = ? ORDER BY name";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    int colour = rs.getInt(6);
                    boolean none = rs.wasNull();
                    list.add(new CustomTag(rs.getLong(1), guildId, rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5), none ? null : colour, delivery(rs.getString(7)),
                            StarboardConfig.readIds(rs.getString(8)), rs.getInt(9), rs.getBoolean(10), rs.getLong(11)));
                }
            }
            return List.copyOf(list);
        } catch (SQLException e) {
            log.error("Failed to read the tags of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the tags", e);
        }
    }

    private static Delivery delivery(String stored) {
        try {
            return Delivery.valueOf(stored);
        } catch (RuntimeException e) {
            return Delivery.CHANNEL;
        }
    }

    public Optional<CustomTag> byName(long guildId, String name) {
        String wanted = CustomTag.normalise(name);
        return forGuild(guildId).stream().filter(t -> t.name().equals(wanted)).findFirst();
    }

    public Optional<CustomTag> get(long guildId, long id) {
        return forGuild(guildId).stream().filter(t -> t.id() == id).findFirst();
    }

    /** Creates the tag when its id is null, otherwise replaces it (the use count is kept). Returns it as saved. */
    public CustomTag save(CustomTag tag) {
        try (Connection c = connectionSupplier.getConnection()) {
            long id;
            if (tag.id() == null) {
                String sql = "INSERT INTO younglings.custom_tag (guild_id, name, content, embed, embed_title, embed_color, delivery, allowed_roles, cooldown_seconds, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setLong(1, tag.guildId());
                    bind(s, 2, tag);
                    try (ResultSet rs = s.executeQuery()) {
                        rs.next();
                        id = rs.getLong(1);
                    }
                }
            } else {
                id = tag.id();
                String sql = "UPDATE younglings.custom_tag SET name = ?, content = ?, embed = ?, embed_title = ?, embed_color = ?, delivery = ?, allowed_roles = ?, cooldown_seconds = ?, enabled = ? WHERE id = ? AND guild_id = ?";
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    bind(s, 1, tag);
                    s.setLong(10, id);
                    s.setLong(11, tag.guildId());
                    s.executeUpdate();
                }
            }
            cache.remove(tag.guildId());
            CacheBus.changed(CACHE_AREA, tag.guildId());
            return get(tag.guildId(), id).orElseThrow();
        } catch (SQLException e) {
            log.error("Failed to save a tag for guild {}", tag.guildId(), e);
            throw new RuntimeException("Failed to save the tag", e);
        }
    }

    /** Binds name, content, embed, title, colour, delivery, roles, cooldown, enabled starting at {@code first}. */
    private static void bind(PreparedStatement s, int first, CustomTag t) throws SQLException {
        int i = first;
        s.setString(i++, t.name());
        s.setString(i++, t.content());
        s.setBoolean(i++, t.embed());
        s.setString(i++, t.embedTitle());
        if (t.embedColor() == null) s.setNull(i++, Types.INTEGER); else s.setInt(i++, t.embedColor());
        s.setString(i++, t.delivery().name());
        s.setString(i++, StarboardConfig.writeIds(t.allowedRoles()));
        s.setInt(i++, t.cooldownSeconds());
        s.setBoolean(i, t.enabled());
    }

    public boolean delete(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.custom_tag WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            boolean gone = s.executeUpdate() > 0;
            cache.remove(guildId);
            CacheBus.changed(CACHE_AREA, guildId);
            return gone;
        } catch (SQLException e) {
            log.error("Failed to delete tag {}", id, e);
            throw new RuntimeException("Failed to delete the tag", e);
        }
    }

    /** Counts a use (and refreshes the server's tags so the dashboard shows it). */
    public void countUse(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.custom_tag SET uses = uses + 1 WHERE guild_id = ? AND id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            s.executeUpdate();
            cache.remove(guildId);
            CacheBus.changed(CACHE_AREA, guildId);
        } catch (SQLException e) {
            log.debug("Couldn't count a tag use: {}", e.getMessage());
        }
    }

    // ---------- cross-process invalidation (CacheBus) ----------

    @Override
    public String cacheArea() {
        return CACHE_AREA;
    }

    @Override
    public void evictCached(long guildId) {
        cache.remove(guildId);
        enabled.remove(guildId);
    }

    @Override
    public void evictAllCached() {
        cache.clear();
        enabled.clear();
    }
}
