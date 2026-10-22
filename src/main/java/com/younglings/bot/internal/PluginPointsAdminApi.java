package com.younglings.bot.internal;

import com.younglings.bot.plugin.PluginEventType;
import com.younglings.bot.plugin.PluginPointsRepository;
import com.younglings.bot.plugin.PluginPointsRules;
import com.younglings.bot.plugin.PluginPointsRules.Rule;
import com.younglings.bot.points.PointsRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The dashboard's "Points for plugin reports" on the Clan plugin page ({@code GET plugin-points}, {@code PUT plugin-points}, T-061): one rule
 * per report type, all off until staff set them. Points are paid only while the server uses points. Reached only through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs the change.
 */
@BService
public class PluginPointsAdminApi implements AdminRouteExtension {
    private final PluginPointsRepository repository;
    private final PointsRepository points;

    public PluginPointsAdminApi(PluginPointsRepository repository, PointsRepository points) {
        this.repository = repository;
        this.points = points;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("plugin-points")) return null;
        long guildId = guild.getIdLong();
        if (method.equals("PUT")) {
            repository.replace(guildId, parse(body.get()), actor.getIdLong(), Instant.now());
        } else {
            AdminRouteExtension.requireMethod(method, "GET");
        }
        return view(guildId);
    }

    /** The rules a PUT sends ({"rules": [{type, points, dailyCap, minValueGp}]}), checked; each type at most once. */
    static List<Rule> parse(DataObject input) {
        if (!input.hasKey("rules") || !input.isType("rules", DataType.ARRAY)) throw AdminRouteExtension.refuse(400, "Send the rules as a list.");
        DataArray given = input.getArray("rules");
        List<Rule> rules = new ArrayList<>();
        Set<PluginEventType> seen = EnumSet.noneOf(PluginEventType.class);
        for (int i = 0; i < given.length(); i++) {
            DataObject r = given.getObject(i);
            PluginEventType type = PluginEventType.parse(r.getString("type", ""));
            if (type == null || !seen.add(type)) throw AdminRouteExtension.refuse(400, "Each kind of report can have one rule.");
            Long min = r.hasKey("minValueGp") && !r.isNull("minValueGp") ? r.getLong("minValueGp") : null;
            Rule rule = new Rule(type, r.getInt("points", 0), r.getInt("dailyCap", 0), type == PluginEventType.DROP ? min : null);
            String problem = PluginPointsRules.problem(rule);
            if (problem != null) throw AdminRouteExtension.refuse(400, problem);
            rules.add(rule);
        }
        return rules;
    }

    private DataObject view(long guildId) {
        List<Rule> saved = repository.allRules(guildId);
        DataArray rows = DataArray.empty();
        for (PluginEventType type : PluginPointsRules.TYPES) {
            Rule rule = saved.stream().filter(r -> r.type() == type).findFirst().orElse(new Rule(type, 0, 0, null));
            DataObject row = DataObject.empty().put("type", type.name()).put("label", PluginPointsRules.label(type)).put("points", rule.points()).put("dailyCap", rule.dailyCap());
            if (type == PluginEventType.DROP) row.put("minValueGp", rule.minValueGp() == null ? PluginPointsRules.DEFAULT_MIN_VALUE_GP : rule.minValueGp());
            rows.add(row);
        }
        var settings = points.settings(guildId);
        return DataObject.empty().put("rules", rows).put("pointsEnabled", settings.enabled()).put("unit", settings.unit()).put("maxPoints", PluginPointsRules.MAX_POINTS);
    }
}
