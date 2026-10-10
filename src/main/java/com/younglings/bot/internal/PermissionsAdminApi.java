package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.permission.PermissionGroup;
import com.younglings.bot.permission.PermissionGroupService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * A server's permission groups for the website: the three built-in levels (Admin, Support, Developer) and any groups the
 * server adds itself, each holding as many server roles as it likes. Reached only through {@link TicketAdminApi}, which has
 * already checked the acting user may use the dashboard here and which audit-logs every change.
 * <p>
 * Reading is open to the Developer tier too. Saving is Admin-only, because it decides who the admins are. A save is checked as
 * a whole before anything changes.
 */
@BService
public class PermissionsAdminApi {
    private static final Logger log = LoggerFactory.getLogger(PermissionsAdminApi.class);

    private final PermissionGroupService groups;
    private final DashboardAccess access;

    public PermissionsAdminApi(PermissionGroupService groups, DashboardAccess access) {
        this.groups = groups;
        this.access = access;
    }

    DataObject get(Guild guild) {
        return toJson(guild, groups.groups(guild.getIdLong()));
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        if (access.tierOf(guild, actor) != DashboardAccess.Tier.ADMIN) throw new ApiError(403, "Only an Admin can change the permission groups.");
        if (body.isNull("groups")) throw new ApiError(400, "No groups were sent.");

        List<String> problems = new ArrayList<>();
        List<PermissionGroupService.Draft> drafts = new ArrayList<>();
        DataArray input = body.getArray("groups");
        for (int i = 0; i < input.length(); i++) {
            DataObject g = input.getObject(i);
            String key = g.isNull("key") || g.getString("key", "").isBlank() ? null : g.getString("key", "");
            String name = g.getString("name", "").strip();
            List<Long> roleIds = new ArrayList<>();
            if (!g.isNull("roleIds")) {
                DataArray roles = g.getArray("roleIds");
                for (int r = 0; r < roles.length(); r++) {
                    String raw = roles.getString(r, "").strip();
                    if (!raw.matches("\\d{1,20}")) {
                        problems.add((name.isEmpty() ? "A group" : "\"" + name + "\"") + " has a role that isn't valid.");
                        continue;
                    }
                    long roleId = Long.parseLong(raw);
                    Role role = guild.getRoleById(roleId);
                    if (role == null) problems.add((name.isEmpty() ? "A group" : "\"" + name + "\"") + " has a role that doesn't exist in this server any more.");
                    else if (role.isPublicRole()) problems.add((name.isEmpty() ? "A group" : "\"" + name + "\"") + " can't use @everyone: that would be everyone.");
                    else roleIds.add(roleId);
                }
            }
            drafts.add(new PermissionGroupService.Draft(key, name, g.getBoolean("includeHigher", false), roleIds));
        }

        try {
            groups.save(guild.getIdLong(), drafts);
        } catch (PermissionGroupService.InvalidGroupsException e) {
            problems.addAll(e.problems());
        }
        if (!problems.isEmpty()) throw new ApiError(400, "Those permission groups can't be saved yet.", problems);

        log.info("Dashboard: {} saved the permission groups", actor.getId());
        return get(guild);
    }

    static DataObject toJson(Guild guild, List<PermissionGroup> list) {
        DataArray array = DataArray.empty();
        for (PermissionGroup g : list) {
            DataArray roles = DataArray.empty();
            g.roleIds().forEach(id -> roles.add(Long.toString(id)));
            array.add(DataObject.empty().put("key", g.key()).put("name", g.name()).put("builtin", g.builtin())
                    .put("includeHigher", g.includeHigher()).put("roleIds", roles));
        }
        return DataObject.empty().put("groups", array)
                .put("maxCustomGroups", PermissionGroupService.MAX_CUSTOM_GROUPS)
                .put("maxRolesPerGroup", PermissionGroupService.MAX_ROLES_PER_GROUP)
                .put("maxNameLength", PermissionGroupService.MAX_NAME_LENGTH);
    }
}
