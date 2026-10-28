package com.runeclans.bot.internal;

import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.servertag.ServerTagConfig;
import com.runeclans.bot.servertag.ServerTagConfig.Action;
import com.runeclans.bot.servertag.ServerTagRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
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
 * The dashboard's Server tags page: the flagged servers, the action, the alert channel, the exempt roles. A save is checked whole before
 * anything is stored. Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this
 * server and audit-logs every change. {@code GET servertag} and {@code PUT servertag}.
 */
@BService
public class ServerTagAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(ServerTagAdminApi.class);

    private final ServerTagRepository repository;

    public ServerTagAdminApi(ServerTagRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("servertag")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        ServerTagConfig c = repository.get(guild.getIdLong());
        DataArray flagged = DataArray.empty();
        c.flaggedGuildIds().forEach(id -> flagged.add(Long.toString(id)));
        DataArray exempt = DataArray.empty();
        c.exemptRoleIds().forEach(id -> exempt.add(Long.toString(id)));
        var self = guild.getSelfMember();
        return DataObject.empty().put("enabled", c.enabled()).put("flaggedGuildIds", flagged).put("action", c.action().name()).put("timeoutMinutes", c.timeoutMinutes())
                .put("alertChannelId", c.alertChannelId() == null ? null : Long.toString(c.alertChannelId())).put("exemptRoleIds", exempt)
                .put("maxFlagged", ServerTagConfig.MAX_FLAGGED).put("maxExemptRoles", ServerTagConfig.MAX_EXEMPT_ROLES)
                .put("canBan", self.hasPermission(Permission.BAN_MEMBERS)).put("canKick", self.hasPermission(Permission.KICK_MEMBERS))
                .put("canTimeout", self.hasPermission(Permission.MODERATE_MEMBERS));
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        ServerTagConfig current = repository.get(guild.getIdLong());
        List<String> problems = new ArrayList<>();
        boolean enabled = body.hasKey("enabled") ? body.getBoolean("enabled", false) : current.enabled();

        List<Long> flagged = current.flaggedGuildIds();
        if (body.hasKey("flaggedGuildIds") && !body.isNull("flaggedGuildIds")) {
            Set<Long> out = new LinkedHashSet<>();
            DataArray input = body.getArray("flaggedGuildIds");
            for (int i = 0; i < input.length(); i++) {
                String raw = input.getString(i, "").strip();
                if (!raw.matches("\\d{15,20}")) problems.add("'" + (raw.length() > 24 ? raw.substring(0, 24) + "…" : raw) + "' isn't a Discord server id (15 to 20 digits).");
                else out.add(Long.parseLong(raw));
            }
            flagged = List.copyOf(out);
        }

        Action action = current.action();
        if (body.hasKey("action")) {
            try {
                action = Action.valueOf(body.getString("action", "").strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add("The action must be alert, timeout, kick or ban.");
            }
        }
        int minutes = body.hasKey("timeoutMinutes") ? body.getInt("timeoutMinutes", -1) : current.timeoutMinutes();

        Long channelId = current.alertChannelId();
        if (body.hasKey("alertChannelId")) {
            channelId = null;
            String raw = body.isNull("alertChannelId") ? "" : body.getString("alertChannelId").strip();
            if (!raw.isEmpty()) {
                if (!raw.matches("\\d{1,20}") || guild.getChannelById(GuildMessageChannel.class, Long.parseLong(raw)) == null) problems.add("The alert channel must be a text channel in this server.");
                else channelId = Long.parseLong(raw);
            }
        }

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

        ServerTagConfig next = new ServerTagConfig(guild.getIdLong(), enabled, flagged, action, minutes, channelId, exempt);
        problems.addAll(next.problems());
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.those_settings_can_t_be_saved"), problems);

        repository.save(next);
        log.info("Dashboard: {} saved the server-tag settings of {} (enabled {}, action {})", actor.getId(), guild.getId(), enabled, action);
        return get(guild);
    }
}
