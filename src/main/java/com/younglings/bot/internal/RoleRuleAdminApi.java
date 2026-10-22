package com.younglings.bot.internal;

import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.rolerules.RoleRuleRepository;
import com.younglings.bot.rolerules.RoleRuleRules;
import com.younglings.bot.rolerules.RoleRuleRules.Event;
import com.younglings.bot.rolerules.RoleRuleRules.Rule;
import com.younglings.bot.rolerules.RoleRuleService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * Connected and trigger role rules for the dashboard ({@code role-rules}). Admin tier only (a Developer login is refused). Reached only through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server, rate-limits writes and audit-logs every change.
 * <ul>
 *   <li>{@code GET role-rules}</li>
 *   <li>{@code POST role-rules} (a new rule) and {@code PUT role-rules/{id}} with {@code {name, event, when, add, remove, enabled}}</li>
 *   <li>{@code DELETE role-rules/{id}}</li>
 * </ul>
 * Every save is checked as a whole (targets, loops with the other rules) and a rule only reacts to role changes from then on; saving one changes nobody.
 */
@BService
public class RoleRuleAdminApi implements AdminRouteExtension {
    private final RoleRuleRepository repository;
    private final RoleRuleService service;
    private final DashboardAccess access;

    public RoleRuleAdminApi(RoleRuleRepository repository, RoleRuleService service, DashboardAccess access) {
        this.repository = repository;
        this.service = service;
        this.access = access;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("role-rules")) return null;
        if (access.tierOf(guild, actor) != DashboardAccess.Tier.ADMIN) throw new ApiError(403, Text.of(guild, "dashboard.only_an_admin_can_use_role"));
        if (parts.length == 1) {
            if (method.equals("POST")) return save(guild, 0, body.get());
            AdminRouteExtension.requireMethod(method, "GET");
            return list(guild);
        }
        if (parts.length == 2) {
            long id = parseId(parts[1]);
            if (method.equals("PUT")) return save(guild, id, body.get());
            AdminRouteExtension.requireMethod(method, "DELETE");
            if (!repository.delete(guild.getIdLong(), id)) throw new ApiError(404, Text.of(guild, "dashboard.that_rule_is_gone"));
            service.changed(guild.getIdLong());
            return list(guild);
        }
        return null;
    }

    DataObject list(Guild guild) {
        DataArray rules = DataArray.empty();
        for (Rule rule : repository.list(guild.getIdLong())) {
            rules.add(DataObject.empty().put("id", Long.toString(rule.id())).put("name", rule.name()).put("event", rule.event().name()).put("enabled", rule.enabled())
                    .put("when", ids(rule.when())).put("add", ids(rule.add())).put("remove", ids(rule.remove())));
        }
        return DataObject.empty().put("rules", rules).put("max", RoleRuleRules.MAX_RULES_PER_SERVER).put("maxWhen", RoleRuleRules.MAX_WHEN_ROLES).put("maxThen", RoleRuleRules.MAX_THEN_ROLES);
    }

    DataObject save(Guild guild, long id, DataObject body) {
        long guildId = guild.getIdLong();
        List<Rule> existing = repository.list(guildId);
        if (id == 0 && existing.size() >= RoleRuleRules.MAX_RULES_PER_SERVER) throw new ApiError(400, Text.of(guild, "dashboard.a_server_can_have_at_most_2", RoleRuleRules.MAX_RULES_PER_SERVER));
        if (id != 0 && existing.stream().noneMatch(r -> r.id() == id)) throw new ApiError(404, Text.of(guild, "dashboard.that_rule_is_gone"));

        Event event = Event.ofKey(body.getString("event", "")).orElseThrow(() -> new ApiError(400, Text.of(guild, "dashboard.choose_whether_the_rule_fires_when")));
        // a rule is only ever saved switched on after it has passed every check below
        Rule rule = new Rule(id, guildId, body.getString("name", "").strip(), event, roleList(body, "when"), roleList(body, "add"), roleList(body, "remove"), body.getBoolean("enabled", false));
        String problem = service.problemWith(guild, rule, existing);
        if (problem != null) throw new ApiError(400, problem);

        if (id == 0) repository.create(rule);
        else if (!repository.save(rule)) throw new ApiError(404, Text.of(guild, "dashboard.that_rule_is_gone"));
        service.changed(guildId);
        return list(guild);
    }

    private static List<Long> roleList(DataObject body, String key) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (body.hasKey(key)) {
            DataArray raw = body.getArray(key);
            for (int i = 0; i < raw.length() && i < 40; i++) ids.add(parseId(raw.getString(i)));
        }
        return new ArrayList<>(ids);
    }

    private static DataArray ids(List<Long> roles) {
        DataArray array = DataArray.empty();
        roles.forEach(r -> array.add(Long.toString(r)));
        return array;
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new ApiError(400, Text.en("dashboard.that_is_not_a_valid_id"));
        }
    }
}
