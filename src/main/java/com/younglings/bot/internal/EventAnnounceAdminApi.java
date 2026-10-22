package com.younglings.bot.internal;

import com.younglings.bot.eventannounce.EventAnnounceRepository;
import com.younglings.bot.eventannounce.EventAnnounceRepository.Tracked;
import com.younglings.bot.eventannounce.EventAnnounceRules;
import com.younglings.bot.eventannounce.EventAnnounceRules.Settings;
import com.younglings.bot.eventannounce.EventAnnounceService;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A server's scheduled event announcer for the website: {@code GET eventannounce} and {@code PUT eventannounce}. Reached only through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class EventAnnounceAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(EventAnnounceAdminApi.class);

    private final EventAnnounceRepository repository;

    public EventAnnounceAdminApi(EventAnnounceRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("eventannounce")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        Settings settings = repository.settings(guild.getIdLong());
        GuildMessageChannel channel = settings.channelId() == null ? null : guild.getChannelById(GuildMessageChannel.class, settings.channelId());
        String problem = settings.channelId() == null ? null : channel == null ? "The channel no longer exists." : EventAnnounceService.problemWith(channel);
        DataArray events = DataArray.empty();
        for (Tracked t : repository.recent(guild.getIdLong(), 10)) {
            ScheduledEvent event = guild.getScheduledEventById(t.eventId());
            events.add(DataObject.empty().put("eventId", Long.toString(t.eventId())).put("name", event == null ? null : event.getName()).put("state", t.state().name())
                    .put("hasRole", t.roleId() != null));
        }
        int roles = guild.getRoles().size();
        return DataObject.empty().put("enabled", settings.enabled()).put("channelId", settings.channelId() == null ? null : Long.toString(settings.channelId()))
                .put("useRole", settings.useRole()).put("notifyStart", settings.notifyStart()).put("problem", problem)
                .put("canManageRoles", guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES)).put("roleCount", roles).put("roleLimit", EventAnnounceRules.ROLE_LIMIT)
                .put("roleHeadroom", EventAnnounceRules.ROLE_HEADROOM).put("roomForRoles", EventAnnounceRules.canMakeRole(roles)).put("events", events);
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        List<String> problems = new ArrayList<>();
        boolean enabled = body.getBoolean("enabled", false);
        boolean useRole = body.getBoolean("useRole", false);
        boolean notifyStart = body.getBoolean("notifyStart", true);
        Long channelId = null;
        String raw = body.isNull("channelId") ? "" : body.getString("channelId", "").strip();
        if (!raw.isEmpty()) {
            GuildMessageChannel channel = raw.matches("\\d{1,20}") ? guild.getChannelById(GuildMessageChannel.class, Long.parseLong(raw)) : null;
            if (channel == null) problems.add("That channel isn't in this server.");
            else {
                String permission = EventAnnounceService.problemWith(channel);
                if (permission != null) problems.add(permission);
                channelId = channel.getIdLong();
            }
        }
        if (enabled && channelId == null) problems.add("Choose the channel to announce events in, or turn the announcer off.");
        if (enabled && useRole && !guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) problems.add("JonnyBot needs the Manage Roles permission to make the interested role for each event.");
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.the_announcer_can_t_be_saved"), problems);
        repository.saveSettings(new Settings(guild.getIdLong(), enabled, channelId, useRole, notifyStart));
        log.info("Dashboard: {} saved the event announcer settings (enabled {})", actor.getId(), enabled);
        return get(guild);
    }
}
