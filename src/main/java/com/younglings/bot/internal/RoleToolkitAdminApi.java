package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.permission.PermissionGroup;
import com.younglings.bot.permission.PermissionGroupService;
import com.younglings.bot.roletools.RoleRules;
import com.younglings.bot.roletools.RoleRules.Mode;
import com.younglings.bot.roletools.RoleToolkitRepository;
import com.younglings.bot.roletools.RoleToolkitRepository.Job;
import com.younglings.bot.roletools.RoleToolkitService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The role toolkit for the dashboard ({@code role-toolkit}). Only an Admin may use any of it (a Developer-tier login is refused). Reached only through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server, rate-limits writes and audit-logs every change.
 * <ul>
 *   <li>{@code GET role-toolkit}: each permission group with the roles its members may give and take, and the recent bulk jobs</li>
 *   <li>{@code PUT role-toolkit/allow/{groupKey}} {@code {roleIds}}</li>
 *   <li>{@code POST role-toolkit/bulk} {@code {mode, sourceRoleId, targetRoleId}}: the DRY RUN. Lists who would change; changes no one</li>
 *   <li>{@code GET role-toolkit/bulk/{id}}: a job with its first names</li>
 *   <li>{@code POST role-toolkit/bulk/{id}/start|pause|cancel}: only these move a job; each is a person pressing a button</li>
 * </ul>
 */
@BService
public class RoleToolkitAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(RoleToolkitAdminApi.class);

    private final RoleToolkitRepository repository;
    private final RoleToolkitService service;
    private final PermissionGroupService groups;

    public RoleToolkitAdminApi(RoleToolkitRepository repository, RoleToolkitService service, PermissionGroupService groups) {
        this.repository = repository;
        this.service = service;
        this.groups = groups;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("role-toolkit")) return null;
        if (!service.isAdmin(guild, actor)) throw new ApiError(403, "Only an Admin can use the role toolkit.");
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return overview(guild);
        }
        if (parts.length == 3 && parts[1].equals("allow")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            return saveAllow(guild, parts[2], body.get());
        }
        if (parts[1].equals("bulk")) {
            if (parts.length == 2) {
                AdminRouteExtension.requireMethod(method, "POST");
                return plan(guild, actor, body.get());
            }
            long id = parseId(parts[2]);
            if (parts.length == 3) {
                AdminRouteExtension.requireMethod(method, "GET");
                return jobJson(guild, job(guild, id), true);
            }
            if (parts.length == 4) {
                AdminRouteExtension.requireMethod(method, "POST");
                return act(guild, id, parts[3]);
            }
        }
        return null;
    }

    DataObject overview(Guild guild) {
        Map<String, Set<Long>> allowed = repository.allowed(guild.getIdLong());
        DataArray groupArray = DataArray.empty();
        for (PermissionGroup group : groups.groups(guild.getIdLong())) {
            DataArray ids = DataArray.empty();
            for (long id : allowed.getOrDefault(group.key(), Set.of())) ids.add(Long.toString(id));
            groupArray.add(DataObject.empty().put("key", group.key()).put("name", group.name()).put("roleIds", ids));
        }
        DataArray jobs = DataArray.empty();
        for (Job job : repository.jobs(guild.getIdLong(), 10)) jobs.add(jobJson(guild, job, false));
        return DataObject.empty().put("groups", groupArray).put("jobs", jobs).put("maxMembers", RoleRules.MAX_BULK_MEMBERS);
    }

    DataObject saveAllow(Guild guild, String groupKey, DataObject body) {
        if (groups.group(guild.getIdLong(), groupKey).isEmpty()) throw new ApiError(404, "That permission group does not exist.");
        if (PermissionGroup.ADMIN.equals(groupKey)) throw new ApiError(400, "Admins can already manage every role the bot can hand out.");
        Set<Long> ids = new HashSet<>();
        List<String> problems = new java.util.ArrayList<>();
        if (body.hasKey("roleIds")) {
            DataArray raw = body.getArray("roleIds");
            for (int i = 0; i < raw.length() && i < 100; i++) {
                long id = parseId(raw.getString(i));
                Role role = guild.getRoleById(id);
                String problem = service.roleRefusal(guild, role);
                if (problem != null) problems.add(problem);
                else ids.add(id);
            }
        }
        if (!problems.isEmpty()) throw new ApiError(400, problems.getFirst(), problems);
        repository.setAllowed(guild.getIdLong(), groupKey, ids);
        return overview(guild);
    }

    DataObject plan(Guild guild, Member actor, DataObject body) {
        Mode mode = Mode.ofKey(body.getString("mode", "")).orElseThrow(() -> new ApiError(400, "Choose whether to give or take the role."));
        Role source = guild.getRoleById(parseId(body.getString("sourceRoleId", "")));
        Role target = guild.getRoleById(parseId(body.getString("targetRoleId", "")));
        if (source == null || target == null) throw new ApiError(400, "Choose two roles from this server.");
        try {
            Job job = service.planBulk(guild, actor, mode, source, target);
            return jobJson(guild, job, true);
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, e.getMessage());
        } catch (SecurityException e) {
            throw new ApiError(403, e.getMessage());
        } catch (Exception e) {
            log.warn("Bulk dry run failed", e);
            throw new ApiError(502, "Discord could not list the members of that role just now. Try again in a moment.");
        }
    }

    DataObject act(Guild guild, long id, String action) {
        Job job = job(guild, id);
        switch (action) {
            case "start" -> {
                String problem = service.start(guild, job);
                if (problem != null) throw new ApiError(409, problem);
            }
            case "pause" -> {
                if (!service.pause(job)) throw new ApiError(409, "Only a running job can be paused.");
            }
            case "cancel" -> {
                if (!service.cancel(job)) throw new ApiError(409, "This job is already finished.");
            }
            default -> throw new ApiError(404, "Not found");
        }
        return jobJson(guild, job(guild, id), true);
    }

    private Job job(Guild guild, long id) {
        return repository.get(guild.getIdLong(), id).orElseThrow(() -> new ApiError(404, "That job does not exist."));
    }

    private DataObject jobJson(Guild guild, Job job, boolean withNames) {
        Role source = guild.getRoleById(job.sourceRoleId());
        Role target = guild.getRoleById(job.targetRoleId());
        DataObject json = DataObject.empty().put("id", Long.toString(job.id())).put("mode", job.mode().name()).put("status", job.status().name())
                .put("sourceRoleId", Long.toString(job.sourceRoleId())).put("sourceName", source == null ? null : source.getName())
                .put("targetRoleId", Long.toString(job.targetRoleId())).put("targetName", target == null ? null : target.getName())
                .put("total", job.total()).put("done", job.done()).put("failed", job.failed()).put("createdAt", job.createdAt().toString());
        if (withNames) {
            DataArray names = DataArray.empty();
            for (long userId : repository.members(job.id(), RoleRules.PREVIEW_NAMES)) {
                Member member = guild.getMemberById(userId);
                names.add(DataObject.empty().put("id", Long.toString(userId)).put("name", member == null ? null : member.getEffectiveName()));
            }
            json.put("preview", names);
        }
        return json;
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new ApiError(400, "That is not a valid id.");
        }
    }
}
