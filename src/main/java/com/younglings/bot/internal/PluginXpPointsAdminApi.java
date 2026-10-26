package com.younglings.bot.internal;

import com.younglings.bot.plugin.PluginPointsRepository;
import com.younglings.bot.plugin.PluginPointsRules;
import com.younglings.bot.plugin.PluginPointsRules.XpRule;
import com.younglings.bot.plugin.PluginXpPoints;
import com.younglings.bot.points.PointsRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * The dashboard's "Points for XP gained" on the Clan plugin page ({@code GET plugin-xp-points}, {@code PUT plugin-xp-points}, T-542): N points for
 * every M XP a member gains in one UTC day, with a daily cap, paid by {@link PluginXpPoints} after the day ends. Off until staff switch it on, and
 * offered only while the bot's {@code PLUGIN_XP_POINTS_ENABLED} and hub client are on ({@code offered}). Reached only through {@link TicketAdminApi},
 * which has already checked the asker may use the dashboard in this server and audit-logs the change.
 */
@BService
public class PluginXpPointsAdminApi implements AdminRouteExtension {
    private final PluginPointsRepository repository;
    private final PointsRepository points;
    private final PluginXpPoints xpPoints;

    public PluginXpPointsAdminApi(PluginPointsRepository repository, PointsRepository points, PluginXpPoints xpPoints) {
        this.repository = repository;
        this.points = points;
        this.xpPoints = xpPoints;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("plugin-xp-points")) return null;
        long guildId = guild.getIdLong();
        if (method.equals("PUT")) {
            XpRule rule = parse(body.get());
            if (rule.enabled() && !xpPoints.offered()) {
                throw AdminRouteExtension.refuse(409, "Points for XP need the bot connected to the RuneClans hub with XP points switched on, and they aren't.");
            }
            repository.saveXpRule(guildId, rule, actor.getIdLong(), Instant.now());
        } else {
            AdminRouteExtension.requireMethod(method, "GET");
        }
        return view(guildId);
    }

    /** The rule a PUT sends ({@code {enabled, points, perXp, dailyCap}}), checked. */
    static XpRule parse(DataObject input) {
        if (!input.hasKey("enabled") || !input.isType("enabled", DataType.BOOLEAN)) throw AdminRouteExtension.refuse(400, "Say whether points for XP are on (enabled: true or false).");
        XpRule rule;
        try {
            rule = new XpRule(input.getBoolean("enabled"), input.getInt("points", 0), input.getLong("perXp", PluginPointsRules.DEFAULT_PER_XP), input.getInt("dailyCap", 0));
        } catch (RuntimeException e) {
            // not a whole number, or out of range for one
            throw AdminRouteExtension.refuse(400, "XP gained: points, XP for each step and daily cap must be whole numbers.");
        }
        String problem = PluginPointsRules.xpProblem(rule);
        if (problem != null) throw AdminRouteExtension.refuse(400, problem);
        return rule;
    }

    private DataObject view(long guildId) {
        XpRule rule = repository.xpRule(guildId);
        var settings = points.settings(guildId);
        return DataObject.empty().put("enabled", rule.enabled()).put("points", rule.points()).put("perXp", rule.perXp()).put("dailyCap", rule.dailyCap())
                .put("offered", xpPoints.offered()).put("pointsEnabled", settings.enabled()).put("unit", settings.unit())
                .put("maxPoints", PluginPointsRules.MAX_POINTS).put("minPerXp", PluginPointsRules.MIN_PER_XP).put("maxPerXp", PluginPointsRules.MAX_PER_XP)
                .put("maxDailyCap", PluginPointsRules.MAX_DAILY_CAP);
    }
}
