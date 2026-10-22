package com.younglings.bot.internal;

import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.runescape.RoleSyncBackfill;
import com.younglings.bot.runescape.RoleSyncBackfill.Entry;
import com.younglings.bot.runescape.RoleSyncBackfill.Plan;
import com.younglings.bot.runescape.RoleSyncBackfill.Progress;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The clan role fix for the dashboard ({@code role-sync}). Admin tier only. Reached only through {@link TicketAdminApi}, which has already checked the
 * asker may use the dashboard in this server, rate-limits writes and audit-logs every change.
 * <ul>
 *   <li>{@code GET role-sync}: how the last run went</li>
 *   <li>{@code POST role-sync/plan}: the DRY RUN. Lists who would be promoted or demoted and changes nobody</li>
 *   <li>{@code POST role-sync/apply} {@code {userIds}}: a person pressed Apply for the people they selected; a fresh plan decides who is really changed</li>
 * </ul>
 */
@BService
public class RoleSyncAdminApi implements AdminRouteExtension {
    private final RoleSyncBackfill backfill;
    private final DashboardAccess access;

    public RoleSyncAdminApi(RoleSyncBackfill backfill, DashboardAccess access) {
        this.backfill = backfill;
        this.access = access;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("role-sync")) return null;
        if (access.tierOf(guild, actor) != DashboardAccess.Tier.ADMIN) throw new ApiError(403, Text.of(guild, "dashboard.only_an_admin_can_fix_clan"));
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return status(guild);
        }
        if (parts.length == 2 && parts[1].equals("plan")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return plan(guild);
        }
        if (parts.length == 2 && parts[1].equals("apply")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return apply(guild, actor, body.get());
        }
        return null;
    }

    DataObject status(Guild guild) {
        Progress p = backfill.progress(guild.getIdLong());
        DataObject json = DataObject.empty().put("max", RoleSyncBackfill.MAX_PER_RUN);
        json.put("progress", p == null ? null : DataObject.empty().put("total", p.total()).put("done", p.done()).put("failed", p.failed()).put("running", p.running()));
        return json;
    }

    DataObject plan(Guild guild) {
        Plan plan = backfill.plan(guild);
        DataArray entries = DataArray.empty();
        for (Entry e : plan.entries()) {
            entries.add(DataObject.empty().put("userId", Long.toString(e.userId())).put("name", e.name()).put("rsn", e.rsn()).put("action", e.action().name())
                    .put("add", roleNames(guild, e.add())).put("remove", roleNames(guild, e.remove())));
        }
        return status(guild).put("entries", entries).put("linkedAccounts", plan.linkedAccounts()).put("notInServer", plan.notInServer()).put("problem", plan.problem());
    }

    DataObject apply(Guild guild, Member actor, DataObject body) {
        Set<Long> selected = new HashSet<>();
        if (body.hasKey("userIds")) {
            DataArray raw = body.getArray("userIds");
            for (int i = 0; i < raw.length() && i <= RoleSyncBackfill.MAX_PER_RUN; i++) {
                try {
                    selected.add(Long.parseLong(raw.getString(i).strip()));
                } catch (NumberFormatException e) {
                    throw new ApiError(400, Text.of(guild, "dashboard.that_is_not_a_valid_id"));
                }
            }
        }
        String problem = backfill.start(guild, selected, actor.getIdLong());
        if (problem != null) throw new ApiError(409, problem);
        return status(guild);
    }

    private static DataArray roleNames(Guild guild, Set<Long> ids) {
        DataArray names = DataArray.empty();
        for (long id : ids) {
            Role role = guild.getRoleById(id);
            names.add(role == null ? "(deleted role)" : role.getName());
        }
        return names;
    }
}
