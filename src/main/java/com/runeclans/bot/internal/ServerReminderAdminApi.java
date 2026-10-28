package com.runeclans.bot.internal;

import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.serverreminder.ServerReminder;
import com.runeclans.bot.serverreminder.ServerReminderService;
import com.runeclans.bot.serverreminder.ServerReminderService.ReminderException;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;

/**
 * A server's channel reminders for the website: {@code GET server-reminders}, {@code POST server-reminders} ({@code channelId}, {@code text}, optional
 * {@code roleId}, {@code startAt} as an ISO time, optional {@code everyMinutes}) and {@code DELETE server-reminders/{id}}. Reached only through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class ServerReminderAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(ServerReminderAdminApi.class);

    private final ServerReminderService service;

    public ServerReminderAdminApi(ServerReminderService service) {
        this.service = service;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("server-reminders")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return list(guild);
            AdminRouteExtension.requireMethod(method, "POST");
            return create(guild, actor, body.get());
        }
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            return cancel(guild, actor, parts[1]);
        }
        return null;
    }

    DataObject list(Guild guild) {
        DataArray reminders = DataArray.empty();
        for (ServerReminder r : service.list(guild.getIdLong())) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, r.channelId());
            var role = r.roleId() == null ? null : guild.getRoleById(r.roleId());
            reminders.add(DataObject.empty().put("id", Long.toString(r.id())).put("channelId", Long.toString(r.channelId())).put("channelName", channel == null ? null : channel.getName())
                    .put("text", r.text()).put("roleId", r.roleId() == null ? null : Long.toString(r.roleId())).put("roleName", role == null ? null : role.getName())
                    .put("everyMinutes", r.every() == null ? null : r.every().toMinutes()).put("nextAt", r.nextAt().toString()).put("status", r.status().name())
                    .put("createdByName", r.createdByName()).put("lastSentAt", r.lastSentAt() == null ? null : r.lastSentAt().toString()).put("sendCount", r.sendCount())
                    .put("error", r.error()));
        }
        return DataObject.empty().put("reminders", reminders).put("maxText", ServerReminder.MAX_TEXT).put("maxActive", ServerReminder.MAX_ACTIVE_PER_GUILD)
                .put("minEveryMinutes", ServerReminder.MIN_EVERY.toMinutes()).put("maxEveryMinutes", ServerReminder.MAX_EVERY.toMinutes());
    }

    DataObject create(Guild guild, Member actor, DataObject body) {
        String channelText = body.isNull("channelId") ? "" : body.getString("channelId", "").strip();
        if (!channelText.matches("\\d{1,20}")) throw new ApiError(400, Text.of(guild, "dashboard.choose_a_channel"));
        Long roleId = null;
        if (body.hasKey("roleId") && !body.isNull("roleId") && !body.getString("roleId", "").isBlank()) {
            String raw = body.getString("roleId").strip();
            if (!raw.matches("\\d{1,20}")) throw new ApiError(400, Text.of(guild, "dashboard.that_role_isn_t_valid"));
            roleId = Long.parseLong(raw);
        }
        Instant firstAt;
        try {
            firstAt = Instant.parse(body.getString("startAt", ""));
        } catch (DateTimeParseException e) {
            throw new ApiError(400, Text.of(guild, "dashboard.choose_when_the_first_post_should"));
        }
        Duration every = null;
        if (body.hasKey("everyMinutes") && !body.isNull("everyMinutes")) {
            long minutes = body.getLong("everyMinutes", 0);
            if (minutes > 0) every = Duration.ofMinutes(minutes);
        }
        try {
            ServerReminder made = service.create(guild, actor.getIdLong(), actor.getEffectiveName(), Long.parseLong(channelText), body.getString("text", ""), roleId, firstAt, every);
            log.info("Dashboard: {} made server reminder {} in channel {} ({})", actor.getId(), made.id(), channelText, every == null ? "once" : "every " + every.toMinutes() + " min");
        } catch (ReminderException e) {
            throw new ApiError(400, e.getMessage());
        }
        return list(guild);
    }

    DataObject cancel(Guild guild, Member actor, String rawId) {
        if (rawId == null || !rawId.strip().matches("\\d{1,18}")) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_valid_reminder"));
        if (!service.cancel(guild.getIdLong(), Long.parseLong(rawId.strip()))) throw new ApiError(404, Text.of(guild, "dashboard.that_reminder_isn_t_active"));
        log.info("Dashboard: {} cancelled server reminder {}", actor.getId(), rawId.strip());
        return list(guild);
    }
}
