package com.younglings.bot.internal;

import com.younglings.bot.anniversary.AnniversaryRepository.Settings;
import com.younglings.bot.anniversary.AnniversaryRules;
import com.younglings.bot.anniversary.AnniversaryRules.Anniversary;
import com.younglings.bot.anniversary.AnniversaryService;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
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
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/**
 * The dashboard's Clan anniversaries page (T-194): GET/PUT {@code anniversaries} (the switch, channel, weekday, hour and minimum years, plus a preview
 * of the coming week), POST {@code anniversaries/send} posts this week's list now. Reached only through {@link TicketAdminApi}, which has already
 * checked the asker may use the dashboard here and audit-logs every change.
 */
@BService
public class AnniversaryAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(AnniversaryAdminApi.class);

    private final AnniversaryService service;

    public AnniversaryAdminApi(AnniversaryService service) {
        this.service = service;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length == 0 || !parts[0].equals("anniversaries")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return view(guild);
            AdminRouteExtension.requireMethod(method, "PUT");
            return save(guild, actor, body.get());
        }
        if (parts.length == 2 && parts[1].equals("send")) {
            AdminRouteExtension.requireMethod(method, "POST");
            String problem = service.sendNow(guild);
            if (problem != null) throw new ApiError(400, problem);
            log.info("Dashboard: {} sent the clan anniversaries now in {}", actor.getId(), guild.getId());
            return view(guild).put("sent", true);
        }
        return null;
    }

    DataObject view(Guild guild) {
        Settings s = service.settings(guild.getIdLong());
        LocalDate from = AnniversaryService.today();
        List<Anniversary> week = service.week(guild.getIdLong(), from, s.minYears());
        DataArray items = DataArray.empty();
        for (Anniversary a : week) items.add(DataObject.empty().put("rsn", a.rsn()).put("years", a.years()).put("date", a.date().toString()));
        return DataObject.empty()
                .put("settings", DataObject.empty().put("enabled", s.enabled()).put("channelId", s.channelId() == null ? null : Long.toString(s.channelId()))
                        .put("weekday", s.weekday().getValue()).put("hour", s.hour()).put("minYears", s.minYears())
                        .put("lastPostedOn", s.lastPostedOn() == null ? null : s.lastPostedOn().toString()))
                .put("preview", DataObject.empty().put("from", from.toString()).put("to", from.plusDays(6).toString()).put("items", items))
                .put("hasRoster", service.hasRoster(guild.getIdLong()))
                .put("limits", DataObject.empty().put("maxMinYears", AnniversaryRules.MAX_MIN_YEARS));
    }

    private DataObject save(Guild guild, Member actor, DataObject body) {
        boolean enabled = body.getBoolean("enabled", false);
        String channel = body.getString("channelId", null);
        Long channelId = null;
        if (channel != null && !channel.isBlank()) {
            try {
                channelId = Long.parseUnsignedLong(channel);
            } catch (NumberFormatException e) {
                throw new ApiError(400, Text.of(guild, "dashboard.that_channel_isn_t_in_this"));
            }
            if (guild.getChannelById(GuildMessageChannel.class, channelId) == null) throw new ApiError(400, Text.of(guild, "dashboard.that_channel_isn_t_in_this"));
        }
        if (enabled && channelId == null) throw new ApiError(400, Text.of(guild, "dashboard.pick_the_channel_to_post_in"));
        DayOfWeek weekday;
        try {
            weekday = DayOfWeek.of(body.getInt("weekday", 1));
        } catch (DateTimeException e) {
            throw new ApiError(400, Text.of(guild, "dashboard.pick_a_day_of_the_week"));
        }
        int hour = body.getInt("hour", 12);
        if (hour < 0 || hour > 23) throw new ApiError(400, Text.of(guild, "dashboard.pick_an_hour_from_0_to"));
        int minYears = body.getInt("minYears", 1);
        if (minYears < 1 || minYears > AnniversaryRules.MAX_MIN_YEARS) throw new ApiError(400, Text.of(guild, "dashboard.the_fewest_years_must_be_1", AnniversaryRules.MAX_MIN_YEARS));
        try {
            service.save(guild.getIdLong(), enabled, channelId, weekday, hour, minYears);
        } catch (SQLException e) {
            log.warn("Could not save anniversary settings for {}", guild.getId(), e);
            throw new ApiError(500, Text.of(guild, "dashboard.that_didn_t_save_try_again"));
        }
        log.info("Dashboard: {} saved clan anniversaries in {} (on: {})", actor.getId(), guild.getId(), enabled);
        return view(guild);
    }
}
