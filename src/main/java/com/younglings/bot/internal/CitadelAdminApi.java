package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.tracking.CitadelWeek;
import com.younglings.bot.tracking.WeeklyDigestService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

/**
 * Who capped at the Clan Citadel in a given week, for the dashboard's wheel page: {@code GET citadel/cappers} is the week that just finished and
 * {@code GET citadel/cappers/<date>} is the week that ended at the reset on that Wednesday. Also lists the last few weeks to pick from. Reached only
 * through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server.
 */
@BService
public class CitadelAdminApi implements AdminRouteExtension {
    private static final int WEEKS_LISTED = 8;

    private final WeeklyDigestService digest;

    public CitadelAdminApi(WeeklyDigestService digest) {
        this.digest = digest;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 2 || parts.length > 3 || !parts[0].equals("citadel") || !parts[1].equals("cappers")) return null;
        AdminRouteExtension.requireMethod(method, "GET");
        return cappers(guild, parts.length == 3 ? parts[2] : null, OffsetDateTime.now(ZoneOffset.UTC));
    }

    DataObject cappers(Guild guild, String endDate, OffsetDateTime now) {
        OffsetDateTime[] window;
        if (endDate == null) {
            window = CitadelWeek.lastCompleted(now);
        } else {
            try {
                LocalDate end = LocalDate.parse(endDate);
                if (end.getDayOfWeek() != java.time.DayOfWeek.WEDNESDAY) throw new ApiError(400, "A Citadel week ends on a Wednesday.");
                window = CitadelWeek.windowEndingOn(end);
            } catch (DateTimeException e) {
                throw new ApiError(400, "Give the date the week ended, like 2026-10-14.");
            }
            if (window[1].isAfter(now.plusDays(7))) throw new ApiError(400, "That week hasn't happened yet.");
        }
        List<String> cappers = digest.getCappedRsns(guild.getIdLong(), window[0], window[1]);
        DataArray names = DataArray.empty();
        cappers.forEach(names::add);

        DataArray weeks = DataArray.empty();
        OffsetDateTime[] latest = CitadelWeek.lastCompleted(now);
        for (int i = 0; i < WEEKS_LISTED; i++) {
            LocalDate end = latest[1].toLocalDate().minusWeeks(i);
            weeks.add(DataObject.empty().put("end", end.toString()).put("start", end.minusDays(7).toString()));
        }
        return DataObject.empty().put("end", window[1].toLocalDate().toString()).put("start", window[0].toLocalDate().toString())
                .put("from", window[0].toString()).put("to", window[1].toString()).put("cappers", names).put("weeks", weeks);
    }
}
