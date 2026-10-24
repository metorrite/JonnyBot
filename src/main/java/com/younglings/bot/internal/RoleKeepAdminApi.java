package com.younglings.bot.internal;

import com.younglings.bot.discord.DiscordLookups;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.rolekeep.RoleKeepRepository;
import com.younglings.bot.rolekeep.RoleKeepRepository.Kept;
import com.younglings.bot.rolekeep.RoleKeepRepository.Temp;
import com.younglings.bot.rolekeep.RoleKeepRules;
import com.younglings.bot.rolekeep.RoleKeepService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Kept roles for the dashboard ({@code role-keeper}): the server's sticky list, the roles kept on individual members, and the timed roles. Reached only
 * through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server, rate-limits writes and audit-logs every change.
 * <ul>
 *   <li>{@code GET role-keeper}</li>
 *   <li>{@code PUT role-keeper/sticky} {@code {roleIds}}</li>
 *   <li>{@code POST role-keeper/kept} {@code {userId, roleId}} and {@code DELETE role-keeper/kept/{id}}</li>
 *   <li>{@code POST role-keeper/temp} {@code {userId, roleId, duration}} and {@code DELETE role-keeper/temp/{id}} (ends it now)</li>
 * </ul>
 * Every route needs an Admin: a Developer login is refused.
 */
@BService
public class RoleKeepAdminApi implements AdminRouteExtension {
    private static final int SHOWN = 100;

    private final RoleKeepRepository repository;
    private final RoleKeepService service;
    private final com.younglings.bot.permission.DashboardAccess access;

    public RoleKeepAdminApi(RoleKeepRepository repository, RoleKeepService service, com.younglings.bot.permission.DashboardAccess access) {
        this.repository = repository;
        this.service = service;
        this.access = access;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("role-keeper")) return null;
        if (access.tierOf(guild, actor) != com.younglings.bot.permission.DashboardAccess.Tier.ADMIN) throw new ApiError(403, Text.of(guild, "dashboard.only_an_admin_can_use_kept"));
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return overview(guild);
        }
        if (parts.length == 2 && parts[1].equals("sticky")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            return saveSticky(guild, body.get());
        }
        if (parts[1].equals("kept")) {
            if (parts.length == 2) {
                AdminRouteExtension.requireMethod(method, "POST");
                return addKept(guild, actor, body.get());
            }
            if (parts.length == 3) {
                AdminRouteExtension.requireMethod(method, "DELETE");
                if (!repository.forgetById(guild.getIdLong(), parseId(parts[2]))) throw new ApiError(404, Text.of(guild, "dashboard.that_entry_is_gone"));
                return overview(guild);
            }
        }
        if (parts[1].equals("temp")) {
            if (parts.length == 2) {
                AdminRouteExtension.requireMethod(method, "POST");
                return addTemp(guild, actor, body.get());
            }
            if (parts.length == 3) {
                AdminRouteExtension.requireMethod(method, "DELETE");
                Temp temp = repository.tempById(guild.getIdLong(), parseId(parts[2])).orElseThrow(() -> new ApiError(404, Text.of(guild, "dashboard.that_timed_role_is_gone")));
                service.endTemp(guild, temp);
                return overview(guild);
            }
        }
        return null;
    }

    DataObject overview(Guild guild) {
        DataArray sticky = DataArray.empty();
        for (long id : repository.sticky(guild.getIdLong())) sticky.add(Long.toString(id));
        DataArray kept = DataArray.empty();
        for (Kept k : repository.recentKept(guild.getIdLong(), SHOWN)) {
            Member member = guild.getMemberById(k.userId());
            kept.add(DataObject.empty().put("id", Long.toString(k.id())).put("userId", Long.toString(k.userId())).put("userName", member == null ? null : member.getEffectiveName())
                    .put("roleId", Long.toString(k.roleId())).put("addedBy", Long.toString(k.addedBy())).put("createdAt", k.createdAt().toString()));
        }
        DataArray temps = DataArray.empty();
        for (Temp t : repository.tempActive(guild.getIdLong(), SHOWN)) {
            Member member = guild.getMemberById(t.userId());
            temps.add(DataObject.empty().put("id", Long.toString(t.id())).put("userId", Long.toString(t.userId())).put("userName", member == null ? null : member.getEffectiveName())
                    .put("roleId", Long.toString(t.roleId())).put("expiresAt", t.expiresAt().toString()));
        }
        return DataObject.empty().put("sticky", sticky).put("kept", kept).put("temp", temps).put("maxSticky", RoleKeepRules.MAX_STICKY_ROLES);
    }

    DataObject saveSticky(Guild guild, DataObject body) {
        Set<Long> ids = new HashSet<>();
        if (body.hasKey("roleIds")) {
            DataArray raw = body.getArray("roleIds");
            for (int i = 0; i < raw.length() && i <= RoleKeepRules.MAX_STICKY_ROLES; i++) ids.add(parseId(raw.getString(i)));
        }
        String problem = service.saveSticky(guild, ids);
        if (problem != null) throw new ApiError(400, problem);
        return overview(guild);
    }

    DataObject addKept(Guild guild, Member actor, DataObject body) {
        Member target = memberOf(guild, body);
        Role role = guild.getRoleById(parseId(body.getString("roleId", "")));
        if (role == null) throw new ApiError(400, Text.of(guild, "dashboard.choose_a_role_from_this_server_2"));
        String problem = service.persist(guild, actor.getIdLong(), target, role);
        if (problem != null) throw new ApiError(400, problem);
        return overview(guild);
    }

    DataObject addTemp(Guild guild, Member actor, DataObject body) {
        Member target = memberOf(guild, body);
        Role role = guild.getRoleById(parseId(body.getString("roleId", "")));
        if (role == null) throw new ApiError(400, Text.of(guild, "dashboard.choose_a_role_from_this_server_2"));
        Duration length = RoleKeepRules.parseDuration(body.getString("duration", "")).orElseThrow(() -> new ApiError(400, Text.of(guild, "dashboard.give_a_length_of_time_like")));
        String problem = service.giveTemp(guild, actor.getIdLong(), target, role, length);
        if (problem != null) throw new ApiError(400, problem);
        return overview(guild);
    }

    private static Member memberOf(Guild guild, DataObject body) {
        long userId = parseId(body.getString("userId", ""));
        Member member = DiscordLookups.memberOrSlow(guild, userId);
        if (member == null) throw new ApiError(404, Text.of(guild, "dashboard.that_person_is_not_in_this"));
        return member;
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new ApiError(400, Text.en("dashboard.that_is_not_a_valid_id"));
        }
    }
}
