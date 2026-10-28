package com.runeclans.bot.honeypot;

import com.runeclans.bot.cachebus.CacheBus;
import com.runeclans.bot.cachebus.CacheInvalidator;
import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.honeypot.HoneypotConfig.Action;
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
public class HoneypotRepository implements CacheInvalidator {
    /** The {@code bot_cache} area of this class's caches (docs/SCALING-STATE.md M10). */
    static final String CACHE_AREA = "honeypot";

    private static final Logger log = LoggerFactory.getLogger(HoneypotRepository.class);

    private final ConnectionSupplier connectionSupplier;
    /** Every message and role event asks for the settings, so they are kept until a save. */
    private final ConcurrentHashMap<Long, HoneypotConfig> cache = new ConcurrentHashMap<>();

    public HoneypotRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "honeypot", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.honeypot_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    channel_id BIGINT NULL,
                    role_id BIGINT NULL,
                    action TEXT NOT NULL DEFAULT 'TIMEOUT',
                    timeout_minutes INTEGER NOT NULL DEFAULT 1440,
                    exempt_role_ids TEXT NOT NULL DEFAULT '',
                    delete_days INTEGER NOT NULL DEFAULT 0
                );
                """));
    }

    /** This server's settings, or the defaults (switched off) if it never saved any. */
    public HoneypotConfig get(long guildId) {
        return cache.computeIfAbsent(guildId, this::read);
    }

    private HoneypotConfig read(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, channel_id, role_id, action, timeout_minutes, exempt_role_ids, delete_days FROM younglings.honeypot_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return HoneypotConfig.defaults(guildId);
                long channel = rs.getLong("channel_id");
                Long channelId = rs.wasNull() ? null : channel;
                long role = rs.getLong("role_id");
                Long roleId = rs.wasNull() ? null : role;
                List<Long> exempt = new ArrayList<>();
                for (String part : rs.getString("exempt_role_ids").split(",")) if (!part.isBlank()) exempt.add(Long.parseLong(part.strip()));
                return new HoneypotConfig(guildId, rs.getBoolean("enabled"), channelId, roleId, Action.valueOf(rs.getString("action")), rs.getInt("timeout_minutes"), List.copyOf(exempt), rs.getInt("delete_days"));
            }
        } catch (SQLException e) {
            log.error("Failed to read the honeypot settings of guild {}", guildId, e);
            // fail closed: if the settings cannot be read, nobody is acted on
            return HoneypotConfig.defaults(guildId);
        }
    }

    public void save(HoneypotConfig config) {
        String sql = """
                INSERT INTO younglings.honeypot_config (guild_id, enabled, channel_id, role_id, action, timeout_minutes, exempt_role_ids, delete_days) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, channel_id = EXCLUDED.channel_id, role_id = EXCLUDED.role_id, action = EXCLUDED.action,
                    timeout_minutes = EXCLUDED.timeout_minutes, exempt_role_ids = EXCLUDED.exempt_role_ids, delete_days = EXCLUDED.delete_days
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, config.guildId());
            s.setBoolean(2, config.enabled());
            if (config.channelId() == null) s.setNull(3, Types.BIGINT); else s.setLong(3, config.channelId());
            if (config.roleId() == null) s.setNull(4, Types.BIGINT); else s.setLong(4, config.roleId());
            s.setString(5, config.action().name());
            s.setInt(6, config.timeoutMinutes());
            s.setString(7, String.join(",", config.exemptRoleIds().stream().map(String::valueOf).toList()));
            s.setInt(8, config.deleteDays());
            s.executeUpdate();
            cache.remove(config.guildId());
            CacheBus.changed(CACHE_AREA, config.guildId());
        } catch (SQLException e) {
            log.error("Failed to save the honeypot settings of guild {}", config.guildId(), e);
            throw new RuntimeException("Failed to save the honeypot settings", e);
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
    }

    @Override
    public void evictAllCached() {
        cache.clear();
    }
}
