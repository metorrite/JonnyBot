package com.younglings.bot.rolerules;

import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.rolerules.RoleRuleRules.Event;
import com.younglings.bot.rolerules.RoleRuleRules.Rule;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Storage for connected and trigger role rules. Role lists are kept as comma separated ids in one column each. */
@BService
public class RoleRuleRepository {
    private static final Logger log = LoggerFactory.getLogger(RoleRuleRepository.class);
    private static final String COLUMNS = "id, guild_id, name, event, when_roles, add_roles, remove_roles, enabled";

    private final ConnectionSupplier connectionSupplier;

    public RoleRuleRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "role rules", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                // No rule exists until an admin makes one, and a new rule is saved switched off.
                """
                CREATE TABLE IF NOT EXISTS younglings.role_rule (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    name TEXT NOT NULL,
                    event TEXT NOT NULL,
                    when_roles TEXT NOT NULL,
                    add_roles TEXT NOT NULL DEFAULT '',
                    remove_roles TEXT NOT NULL DEFAULT '',
                    enabled BOOLEAN NOT NULL DEFAULT FALSE
                );
                """,
                "CREATE INDEX IF NOT EXISTS role_rule_guild_idx ON younglings.role_rule (guild_id);"
        ));
    }

    public List<Rule> list(long guildId) {
        List<Rule> rules = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM younglings.role_rule WHERE guild_id = ? ORDER BY id")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) read(rs).ifPresent(rules::add);
            }
            return rules;
        } catch (SQLException e) {
            throw fail("read the role rules", e);
        }
    }

    public Optional<Rule> get(long guildId, long id) {
        return list(guildId).stream().filter(r -> r.id() == id).findFirst();
    }

    /** Adds a rule (its id is ignored) and returns it with its id. */
    public Rule create(Rule rule) {
        String sql = "INSERT INTO younglings.role_rule (guild_id, name, event, when_roles, add_roles, remove_roles, enabled) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, rule.guildId());
            s.setString(2, rule.name());
            s.setString(3, rule.event().name());
            s.setString(4, join(rule.when()));
            s.setString(5, join(rule.add()));
            s.setString(6, join(rule.remove()));
            s.setBoolean(7, rule.enabled());
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return new Rule(rs.getLong(1), rule.guildId(), rule.name(), rule.event(), rule.when(), rule.add(), rule.remove(), rule.enabled());
            }
        } catch (SQLException e) {
            throw fail("create the role rule", e);
        }
    }

    /** Replaces the rule's contents, in its own server only. */
    public boolean save(Rule rule) {
        String sql = "UPDATE younglings.role_rule SET name = ?, event = ?, when_roles = ?, add_roles = ?, remove_roles = ?, enabled = ? WHERE id = ? AND guild_id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, rule.name());
            s.setString(2, rule.event().name());
            s.setString(3, join(rule.when()));
            s.setString(4, join(rule.add()));
            s.setString(5, join(rule.remove()));
            s.setBoolean(6, rule.enabled());
            s.setLong(7, rule.id());
            s.setLong(8, rule.guildId());
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("save the role rule", e);
        }
    }

    public boolean delete(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.role_rule WHERE id = ? AND guild_id = ?")) {
            s.setLong(1, id);
            s.setLong(2, guildId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw fail("delete the role rule", e);
        }
    }

    // ---------- plumbing ----------

    static String join(List<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }

    static List<Long> split(String text) {
        List<Long> ids = new ArrayList<>();
        if (text == null || text.isBlank()) return ids;
        for (String part : text.split(",")) {
            try {
                ids.add(Long.parseLong(part.strip()));
            } catch (NumberFormatException ignored) {
                // a damaged entry is skipped; a rule with fewer roles can only do less
            }
        }
        return ids;
    }

    /** A row whose event is unknown is left out, so it can never fire. */
    private static Optional<Rule> read(ResultSet rs) throws SQLException {
        Optional<Event> event = Event.ofKey(rs.getString("event"));
        if (event.isEmpty()) return Optional.empty();
        return Optional.of(new Rule(rs.getLong("id"), rs.getLong("guild_id"), rs.getString("name"), event.get(), split(rs.getString("when_roles")), split(rs.getString("add_roles")),
                split(rs.getString("remove_roles")), rs.getBoolean("enabled")));
    }

    private static RuntimeException fail(String what, SQLException e) {
        log.error("Failed to {}", what, e);
        return new RuntimeException("Failed to " + what, e);
    }
}
