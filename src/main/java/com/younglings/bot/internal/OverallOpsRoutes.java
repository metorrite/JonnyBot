package com.younglings.bot.internal;

import com.younglings.bot.notice.BotOwners;
import com.younglings.bot.plugin.RuneSyncService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * The bot's own health and RuneMetrics budget for the overall admin panel ({@code overall/health}, {@code overall/runemetrics-budget}) the plans per server ({@code overall/premium-guilds...}, the bot's owner only, as before) and the RuneSync
 * diagnostics viewer ({@code overall/runesync/diagnostics/{id}}, the bot's owner only as before). Read only: the
 * same answers the bot owner's dashboard gives, here for every overall admin, who need not be in any server. {@link OverallAdminApi} has already
 * checked the asker is an overall admin; the answers are about the whole bot, with the home server only supplying the roster and the words.
 */
@BService
public class OverallOpsRoutes {
    static final String HEALTH = "health";
    static final String BUDGET = "runemetrics-budget";
    static final String RUNESYNC = "runesync";
    static final String PREMIUM_GUILDS = "premium-guilds";

    private final AdminOpsApi ops;
    private final RuneMetricsBudgetAdminApi budget;
    private final RuneSyncService runeSync;
    private final BotOwners owners;
    private final PremiumAdminApi premium;

    public OverallOpsRoutes(AdminOpsApi ops, RuneMetricsBudgetAdminApi budget, RuneSyncService runeSync, BotOwners owners, PremiumAdminApi premium) {
        this.ops = ops;
        this.budget = budget;
        this.runeSync = runeSync;
        this.owners = owners;
        this.premium = premium;
    }

    /** {@code null} when the path is not one of these. */
    DataObject route(JDA jda, long actor, Guild home, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length >= 1 && parts[0].equals(PREMIUM_GUILDS)) return premium.routeFor(home, actor, method, parts, body);
        if (parts.length == 3 && parts[0].equals(RUNESYNC) && parts[1].equals("diagnostics")) return diagnostic(jda, actor, method, parts[2]);
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

    /** One snapshot a member sent from RuneSync's panel, redacted when stored. The bot's owner only, whether or not the id exists. */
    private DataObject diagnostic(JDA jda, long actor, String method, String id) {
        if (!owners.isOwner(jda, actor)) throw AdminRouteExtension.refuse(403, "Only the bot's owner can open RuneSync diagnostics.");
        AdminRouteExtension.requireMethod(method, "GET");
        try {
            DataObject found = runeSync.diagnostic(id, Instant.now());
            if (found == null) throw AdminRouteExtension.refuse(404, "No snapshot has that id (they are kept " + RuneSyncService.SNAPSHOT_KEPT.toDays() + " days).");
            return found;
        } catch (RuneSyncService.RuneSyncException e) {
            throw AdminRouteExtension.refuse(e.status(), e.getMessage());
        }
    }
}
