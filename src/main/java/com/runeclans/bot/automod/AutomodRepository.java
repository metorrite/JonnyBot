package com.runeclans.bot.automod;

import com.runeclans.bot.automod.AutomodRules.Rule;
import com.runeclans.bot.automod.AutomodRules.RuleConfig;
import com.runeclans.bot.cachebus.CacheBus;
import com.runeclans.bot.cachebus.CacheInvalidator;
import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** A server's automod settings. Every message in a server asks for them, so they are kept in memory and refreshed whenever they change. */
@BService
public class AutomodRepository implements CacheInvalidator {
    /** The {@code bot_cache} area of this class's caches (docs/SCALING-STATE.md M10). */
    static final String CACHE_AREA = "automod";

    private static final Logger log = LoggerFactory.getLogger(AutomodRepository.class);

    /** {@code exemptRoles} and {@code exemptChannels} are never checked, whatever they post. */
    public record Config(long guildId, boolean enabled, Set<Long> exemptRoles, Set<Long> exemptChannels, Map<Rule, RuleConfig> rules) {
        public static Config defaults(long guildId) {
            return new Config(guildId, false, Set.of(), Set.of(), AutomodRules.allDefaults());
        }
    }

    private final ConnectionSupplier connectionSupplier;
    private final Map<Long, Config> cache = new ConcurrentHashMap<>();

    public AutomodRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "automod", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.automod_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    exempt_roles TEXT NOT NULL DEFAULT '',
                    exempt_channels TEXT NOT NULL DEFAULT '',
                    rules TEXT NOT NULL DEFAULT '{}'
                );
                """));
    }

    public Config get(long guildId) {
        return cache.computeIfAbsent(guildId, this::load);
    }

    private Config load(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT enabled, exempt_roles, exempt_channels, rules FROM younglings.automod_config WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Config.defaults(guildId);
                return new Config(guildId, rs.getBoolean(1), ids(rs.getString(2)), ids(rs.getString(3)), parse(rs.getString(4)));
            }
        } catch (SQLException e) {
            log.error("Failed to read the automod settings of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the automod settings", e);
        }
    }

    public void save(Config config) {
        String sql = """
                INSERT INTO younglings.automod_config (guild_id, enabled, exempt_roles, exempt_channels, rules) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (guild_id) DO UPDATE SET enabled = EXCLUDED.enabled, exempt_roles = EXCLUDED.exempt_roles,
                    exempt_channels = EXCLUDED.exempt_channels, rules = EXCLUDED.rules
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, config.guildId());
            s.setBoolean(2, config.enabled());
            s.setString(3, join(config.exemptRoles()));
            s.setString(4, join(config.exemptChannels()));
            s.setString(5, write(config.rules()));
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the automod settings", e);
        }
        cache.put(config.guildId(), config);
        CacheBus.changed(CACHE_AREA, config.guildId());
    }

    // ---------- storage format ----------

    private static Set<Long> ids(String raw) {
        Set<Long> ids = new LinkedHashSet<>();
        if (raw == null) return ids;
        for (String part : raw.split(",")) {
            try {
                if (!part.isBlank()) ids.add(Long.parseLong(part.strip()));
            } catch (NumberFormatException ignored) {
                // a damaged entry is dropped rather than breaking the whole config
            }
        }
        return ids;
    }

    private static String join(Set<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }

    static String write(Map<Rule, RuleConfig> rules) {
        DataObject json = DataObject.empty();
        for (Rule rule : Rule.values()) {
            RuleConfig c = rules.getOrDefault(rule, AutomodRules.defaults(rule));
            DataArray words = DataArray.empty();
            c.words().forEach(words::add);
            json.put(rule.name(), DataObject.empty().put("enabled", c.enabled()).put("warn", c.warn()).put("limit", c.limit()).put("seconds", c.seconds()).put("words", words)
                    .put("response", c.response()).put("logChannel", Long.toString(c.logChannelId())));
        }
        return json.toString();
    }

    static Map<Rule, RuleConfig> parse(String raw) {
        Map<Rule, RuleConfig> rules = new EnumMap<>(Rule.class);
        DataObject json;
        try {
            json = DataObject.fromJson(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (RuntimeException e) {
            json = DataObject.empty();
        }
        for (Rule rule : Rule.values()) {
            RuleConfig fallback = AutomodRules.defaults(rule);
            if (!json.hasKey(rule.name()) || json.isNull(rule.name())) {
                rules.put(rule, fallback);
                continue;
            }
            try {
                DataObject o = json.getObject(rule.name());
                List<String> words = new ArrayList<>();
                if (o.hasKey("words") && !o.isNull("words")) {
                    DataArray array = o.getArray("words");
                    for (int i = 0; i < array.length(); i++) words.add(array.getString(i));
                }
                long logChannel = 0;
                try {
                    logChannel = Long.parseLong(o.getString("logChannel", "0"));
                } catch (NumberFormatException ignored) {
                    // a damaged entry means no log channel
                }
                rules.put(rule, new RuleConfig(o.getBoolean("enabled", false), o.getBoolean("warn", false), o.getInt("limit", fallback.limit()), o.getInt("seconds", fallback.seconds()), words,
                        o.getString("response", ""), logChannel));
            } catch (RuntimeException e) {
                rules.put(rule, fallback);
            }
        }
        return rules;
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
