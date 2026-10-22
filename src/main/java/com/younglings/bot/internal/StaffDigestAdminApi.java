package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.staffdigest.StaffDigestRepository.Settings;
import com.younglings.bot.staffdigest.StaffDigestRules;
import com.younglings.bot.staffdigest.StaffDigestRules.EventLine;
import com.younglings.bot.staffdigest.StaffDigestRules.Facts;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.util.function.Supplier;

/**
 * The dashboard's Staff digest page (T-193): GET/PUT {@code staff-digest} (the switch, channel, weekday and hour, plus what this week's digest would
 * say), POST {@code staff-digest/send} posts it now. Reached only through {@link TicketAdminApi}, which has already checked the asker may use the
 * dashboard here and audit-logs every change.
 */
@BService
public class StaffDigestAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(StaffDigestAdminApi.class);

    private final StaffDigestService service;

    public StaffDigestAdminApi(StaffDigestService service) {
        this.service = service;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length == 0 || !parts[0].equals("staff-digest")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return view(guild);
            AdminRouteExtension.requireMethod(method, "PUT");
            return save(guild, actor, body.get());
        }
        if (parts.length == 2 && parts[1].equals("send")) {
            AdminRouteExtension.requireMethod(method, "POST");
            String problem = service.sendNow(guild);
            if (problem != null) throw new ApiError(400, problem);
            log.info("Dashboard: {} sent the staff digest now in {}", actor.getId(), guild.getId());
            return view(guild).put("sent", true);
        }
        return null;
    }

    DataObject view(Guild guild) {
        Settings s = service.settings(guild.getIdLong());
        Facts f = service.facts(guild);
        DataArray events = DataArray.empty();
        for (EventLine e : f.events()) events.add(DataObject.empty().put("name", e.name()).put("start", e.start().toString()));
        DataObject facts = DataObject.empty().put("rosterSize", f.rosterSize()).put("inactive", f.inactive()).put("unverified", f.unverified())
                .put("promotionsDue", f.promotionsDue()).put("stale", f.stale()).put("openTickets", f.openTickets()).put("escalated", f.escalated())
                .put("events", events).put("maxEvents", StaffDigestRules.MAX_EVENTS);
        return DataObject.empty()
                .put("settings", DataObject.empty().put("enabled", s.enabled()).put("channelId", s.channelId() == null ? null : Long.toString(s.channelId()))
                        .put("weekday", s.weekday().getValue()).put("hour", s.hour()).put("lastPostedOn", s.lastPostedOn() == null ? null : s.lastPostedOn().toString()))
                .put("preview", facts);
    }

    private DataObject save(Guild guild, Member actor, DataObject body) {
        boolean enabled = body.getBoolean("enabled", false);
        String channel = body.getString("channelId", null);
        Long channelId = null;
        if (channel != null && !channel.isBlank()) {
            try {
                channelId = Long.parseUnsignedLong(channel);
            } catch (NumberFormatException e) {
                throw new ApiError(400, "That channel isn't in this server.");
            }
            if (guild.getChannelById(GuildMessageChannel.class, channelId) == null) throw new ApiError(400, "That channel isn't in this server.");
        }
        if (enabled && channelId == null) throw new ApiError(400, "Pick the staff channel to post in.");
        DayOfWeek weekday;
        try {
            weekday = DayOfWeek.of(body.getInt("weekday", 1));
        } catch (DateTimeException e) {
            throw new ApiError(400, "Pick a day of the week.");
        }
        int hour = body.getInt("hour", 9);
        if (hour < 0 || hour > 23) throw new ApiError(400, "Pick an hour from 0 to 23.");
        try {
            service.repository().save(guild.getIdLong(), enabled, channelId, weekday, hour);
        } catch (SQLException e) {
            log.warn("Could not save staff digest settings for {}", guild.getId(), e);
            throw new ApiError(500, "That didn't save. Try again in a moment.");
        }
        log.info("Dashboard: {} saved the staff digest in {} (on: {})", actor.getId(), guild.getId(), enabled);
        return view(guild);
    }
}
