package com.younglings.bot.internal;

import com.younglings.bot.honeypot.HoneypotConfig;
import com.younglings.bot.honeypot.HoneypotConfig.Action;
import com.younglings.bot.honeypot.HoneypotRepository;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The dashboard's Honeypot page: the decoy channel and role, the action, the exempt roles. A save is checked whole before anything is stored.
 * Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs
 * every change. {@code GET honeypot} and {@code PUT honeypot}; the reply also says what JonnyBot may do, since each action needs its own permission.
 */
@BService
public class HoneypotAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(HoneypotAdminApi.class);

    private final HoneypotRepository repository;

    public HoneypotAdminApi(HoneypotRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("honeypot")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        HoneypotConfig c = repository.get(guild.getIdLong());
        DataArray exempt = DataArray.empty();
        c.exemptRoleIds().forEach(id -> exempt.add(Long.toString(id)));
        var self = guild.getSelfMember();
        return DataObject.empty().put("enabled", c.enabled()).put("channelId", c.channelId() == null ? null : Long.toString(c.channelId()))
                .put("roleId", c.roleId() == null ? null : Long.toString(c.roleId())).put("action", c.action().name()).put("timeoutMinutes", c.timeoutMinutes())
                .put("exemptRoleIds", exempt).put("deleteDays", c.deleteDays()).put("maxExemptRoles", HoneypotConfig.MAX_EXEMPT_ROLES)
                .put("canBan", self.hasPermission(Permission.BAN_MEMBERS)).put("canKick", self.hasPermission(Permission.KICK_MEMBERS))
                .put("canTimeout", self.hasPermission(Permission.MODERATE_MEMBERS));
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        HoneypotConfig current = repository.get(guild.getIdLong());
        List<String> problems = new ArrayList<>();
        boolean enabled = body.hasKey("enabled") ? body.getBoolean("enabled", false) : current.enabled();

        Long channelId = current.channelId();
        if (body.hasKey("channelId")) {
            channelId = null;
            String raw = body.isNull("channelId") ? "" : body.getString("channelId").strip();
            if (!raw.isEmpty()) {
                if (!raw.matches("\\d{1,20}") || guild.getChannelById(TextChannel.class, Long.parseLong(raw)) == null) problems.add("The decoy channel must be a text channel in this server.");
                else channelId = Long.parseLong(raw);
            }
        }

        Long roleId = current.roleId();
        if (body.hasKey("roleId")) {
            roleId = null;
            String raw = body.isNull("roleId") ? "" : body.getString("roleId").strip();
            if (!raw.isEmpty()) {
                Role role = raw.matches("\\d{1,20}") ? guild.getRoleById(Long.parseLong(raw)) : null;
                if (role == null) problems.add("The decoy role doesn't exist in this server.");
                else if (role.isPublicRole()) problems.add("@everyone can't be the decoy role.");
                else if (role.isManaged()) problems.add("A role an integration manages can't be the decoy role.");
                else roleId = role.getIdLong();
            }
        }

        Action action = current.action();
        if (body.hasKey("action")) {
            try {
                action = Action.valueOf(body.getString("action", "").strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add("The action must be timeout, kick or ban.");
            }
        }
        int minutes = body.hasKey("timeoutMinutes") ? body.getInt("timeoutMinutes", -1) : current.timeoutMinutes();
        int days = body.hasKey("deleteDays") ? body.getInt("deleteDays", -1) : current.deleteDays();

        List<Long> exempt = current.exemptRoleIds();
        if (body.hasKey("exemptRoleIds") && !body.isNull("exemptRoleIds")) {
            Set<Long> out = new LinkedHashSet<>();
            DataArray input = body.getArray("exemptRoleIds");
            for (int i = 0; i < input.length(); i++) {
                String raw = input.getString(i, "").strip();
                if (!raw.matches("\\d{1,20}") || guild.getRoleById(Long.parseLong(raw)) == null) problems.add("An exempt role doesn't exist in this server any more.");
                else out.add(Long.parseLong(raw));
            }
            exempt = List.copyOf(out);
        }

        HoneypotConfig next = new HoneypotConfig(guild.getIdLong(), enabled, channelId, roleId, action, minutes, exempt, days);
        problems.addAll(next.problems());
        if (enabled && roleId != null && exempt.contains(roleId)) problems.add("The decoy role can't also be an exempt role.");
        if (!problems.isEmpty()) throw new ApiError(400, "Those settings can't be saved yet.", problems);

        repository.save(next);
        log.info("Dashboard: {} saved the honeypot settings of {} (enabled {}, action {})", actor.getId(), guild.getId(), enabled, action);
        return get(guild);
    }
}
