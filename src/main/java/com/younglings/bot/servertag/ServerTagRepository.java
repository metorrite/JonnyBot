package com.younglings.bot.servertag;

import com.younglings.bot.cachebus.CacheBus;
import com.younglings.bot.cachebus.CacheInvalidator;
import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.servertag.ServerTagConfig.Action;
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
import java.util.concurrent.ConcurrentHashMap;

@BService
public class ServerTagRepository implements CacheInvalidator {
    /** The {@code bot_cache} area of this class's caches (docs/SCALING-STATE.md M10). */
    static final String CACHE_AREA = "server-tag";

    private static final Logger log = LoggerFactory.getLogger(ServerTagRepository.class);

    private final ConnectionSupplier connectionSupplier;
    /** Every join and profile change asks for the settings, so they are kept until a save. */
    private final ConcurrentHashMap<Long, ServerTagConfig> cache = new ConcurrentHashMap<>();

    public ServerTagRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "server tag", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.server_tag_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    flagged_guild_ids TEXT NOT NULL DEFAULT '',
                    action TEXT NOT NULL DEFAULT 'ALERT',
                    timeout_minutes INTEGER NOT NULL DEFAULT 1440,
                    alert_channel_id BIGINT NULL,
                    exempt_role_ids TEXT NOT NULL DEFAULT ''
                );
                """));
    }

    /** This server's settings, or the defaults (switched off) if it never saved any. */
    public ServerTagConfig get(long guildId) {
        return cache.computeIfAbsent(guildId, this::read);
    }

    private ServerTagConfig read(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, flagged_guild_ids, action, timeout_minutes, alert_channel_id, exempt_role_ids FROM younglings.server_tag_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return ServerTagConfig.defaults(guildId);
                long channel = rs.getLong("alert_channel_id");
                Long channelId = rs.wasNull() ? null : channel;
                return new ServerTagConfig(guildId, rs.getBoolean("enabled"), ids(rs.getString("flagged_guild_ids")), Action.valueOf(rs.getString("action")),
                        rs.getInt("timeout_minutes"), channelId, ids(rs.getString("exempt_role_ids")));
            }
        } catch (SQLException e) {
            log.error("Failed to read the server-tag settings of guild {}", guildId, e);
            // fail closed: if the settings cannot be read, nobody is acted on
            return ServerTagConfig.defaults(guildId);
        }
    }

    public void save(ServerTagConfig config) {
        String sql = """
                INSERT INTO younglings.server_tag_config (guild_id, enabled, flagged_guild_ids, action, timeout_minutes, alert_channel_id, exempt_role_ids) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, flagged_guild_ids = EXCLUDED.flagged_guild_ids, action = EXCLUDED.action,
                    timeout_minutes = EXCLUDED.timeout_minutes, alert_channel_id = EXCLUDED.alert_channel_id, exempt_role_ids = EXCLUDED.exempt_role_ids
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, config.guildId());
            s.setBoolean(2, config.enabled());
            s.setString(3, join(config.flaggedGuildIds()));
            s.setString(4, config.action().name());
            s.setInt(5, config.timeoutMinutes());
            if (config.alertChannelId() == null) s.setNull(6, Types.BIGINT); else s.setLong(6, config.alertChannelId());
            s.setString(7, join(config.exemptRoleIds()));
            s.executeUpdate();
            cache.remove(config.guildId());
            CacheBus.changed(CACHE_AREA, config.guildId());
        } catch (SQLException e) {
            log.error("Failed to save the server-tag settings of guild {}", config.guildId(), e);
            throw new RuntimeException("Failed to save the server-tag settings", e);
        }
    }

    private static List<Long> ids(String text) {
        List<Long> out = new ArrayList<>();
        for (String part : text.split(",")) if (!part.isBlank()) out.add(Long.parseLong(part.strip()));
        return List.copyOf(out);
    }

    private static String join(List<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }

    // ---------- cross-process invalidation (CacheBus) ----------

    @Override
    public String cacheArea() {
        return CACHE_AREA;
    }

    @Override
    public void evictCached(long guildId) {
        cache.remove(guildId);
    }

    @Override
    public void evictAllCached() {
        cache.clear();
    }
}
