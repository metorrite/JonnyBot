package com.younglings.bot.internal;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;

/**
 * The bot's own health and RuneMetrics budget for the overall admin panel ({@code overall/health}, {@code overall/runemetrics-budget}). Read only: the
 * same answers the bot owner's dashboard gives, here for every overall admin, who need not be in any server. {@link OverallAdminApi} has already
 * checked the asker is an overall admin; the answers are about the whole bot, with the home server only supplying the roster and the words.
 */
@BService
public class OverallOpsRoutes {
    static final String HEALTH = "health";
    static final String BUDGET = "runemetrics-budget";

    private final AdminOpsApi ops;
    private final RuneMetricsBudgetAdminApi budget;

    public OverallOpsRoutes(AdminOpsApi ops, RuneMetricsBudgetAdminApi budget) {
        this.ops = ops;
        this.budget = budget;
    }

    /** {@code null} when the path is not one of these. */
    DataObject route(Guild home, String method, String[] parts) {
        if (parts.length != 1) return null;
        if (parts[0].equals(HEALTH)) {
            AdminRouteExtension.requireMethod(method, "GET");
            return ops.health(home);
        }
        if (parts[0].equals(BUDGET)) {
            AdminRouteExtension.requireMethod(method, "GET");
            return budget.overview(home);
        }
        return null;
    }
}
