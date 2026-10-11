package com.younglings.bot.autodelete;

import com.younglings.bot.autodelete.AutoDeleteRule.Filter;
import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Auto-delete rules, kept in memory per server too (every message in a server asks for them) and refreshed whenever they change. */
@BService
public class AutoDeleteRepository {
    private static final Logger log = LoggerFactory.getLogger(AutoDeleteRepository.class);

    private final ConnectionSupplier connectionSupplier;
    /** guild -> channel -> rule */
    private final Map<Long, Map<Long, AutoDeleteRule>> cache = new ConcurrentHashMap<>();

    public AutoDeleteRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "autodelete", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.autodelete_rule (
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    filters TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY (guild_id, channel_id)
                );
                """));
    }

    /** The rule for one channel, from memory after the first look; null if the channel has none. */
    public AutoDeleteRule forChannel(long guildId, long channelId) {
        return byChannel(guildId).get(channelId);
    }

    public List<AutoDeleteRule> forGuild(long guildId) {
        return byChannel(guildId).values().stream().sorted(java.util.Comparator.comparingLong(AutoDeleteRule::channelId)).toList();
    }

    private Map<Long, AutoDeleteRule> byChannel(long guildId) {
        return cache.computeIfAbsent(guildId, this::load);
    }

    private Map<Long, AutoDeleteRule> load(long guildId) {
        Map<Long, AutoDeleteRule> rules = new ConcurrentHashMap<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT channel_id, filters FROM younglings.autodelete_rule WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) rules.put(rs.getLong("channel_id"), new AutoDeleteRule(guildId, rs.getLong("channel_id"), parse(rs.getString("filters"))));
            }
            return rules;
        } catch (SQLException e) {
            log.error("Failed to read the auto-delete rules of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the auto-delete rules", e);
        }
    }

    public void save(AutoDeleteRule rule) {
        String sql = """
                INSERT INTO younglings.autodelete_rule (guild_id, channel_id, filters) VALUES (?, ?, ?)
                ON CONFLICT (guild_id, channel_id) DO UPDATE SET filters = EXCLUDED.filters
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, rule.guildId());
            s.setLong(2, rule.channelId());
            s.setString(3, write(rule.filters()));
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the auto-delete rule", e);
        } finally {
            cache.remove(rule.guildId());
        }
    }

    public boolean delete(long guildId, long channelId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.autodelete_rule WHERE guild_id = ? AND channel_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, channelId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the auto-delete rule", e);
        } finally {
            cache.remove(guildId);
        }
    }

    static Set<Filter> parse(String text) {
        Set<Filter> filters = EnumSet.noneOf(Filter.class);
        if (text == null || text.isBlank()) return filters;
        for (String part : text.split(",")) {
            try {
                filters.add(Filter.valueOf(part.strip()));
            } catch (IllegalArgumentException ignored) {
                // a filter this version doesn't know is skipped rather than breaking the whole rule
            }
        }
        return filters;
    }

    static String write(Set<Filter> filters) {
        List<String> names = new ArrayList<>();
        for (Filter f : Filter.values()) if (filters.contains(f)) names.add(f.name());
        return String.join(",", names);
    }
}
