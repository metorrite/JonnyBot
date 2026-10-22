package com.younglings.bot.internal;

import com.younglings.bot.absence.AbsenceRepository.Notice;
import com.younglings.bot.absence.AbsenceRules;
import com.younglings.bot.absence.AbsenceService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * A member's own leave of absence on their website profile (T-190), under {@code /internal/me/absence}: GET shows it, PUT sets it
 * ({@code until} as yyyy-mm-dd, optional {@code reason}), DELETE ends it. The same notice {@code /absence} sets. Only ever the asking member's row.
 */
@BService
public class AbsenceMemberApi implements MemberRouteExtension {
    private final AbsenceService service;

    public AbsenceMemberApi(AbsenceService service) {
        this.service = service;
    }

    @Override
    public DataObject route(Guild guild, long userId, String method, String route, DataObject body) {
        if (!route.equals("absence")) return null;
        long guildId = guild.getIdLong();
        switch (method) {
            case "GET" -> {
                return view(guildId, userId);
            }
            case "PUT" -> {
                if (!service.enabled(guildId)) throw MemberRouteExtension.refuse(409, "Leave of absence isn't switched on in this server.");
                LocalDate until;
                try {
                    until = LocalDate.parse(body.getString("until", ""));
                } catch (DateTimeParseException e) {
                    throw MemberRouteExtension.refuse(400, "Pick the day you'll be back.");
                }
                AbsenceService.Outcome outcome = service.set(guildId, userId, until, body.getString("reason", ""));
                if (!outcome.ok()) throw MemberRouteExtension.refuse(400, outcome.problem());
                return view(guildId, userId);
            }
            case "DELETE" -> {
                service.end(guildId, userId);
                return view(guildId, userId);
            }
            default -> {
                return null;
            }
        }
    }

    private DataObject view(long guildId, long userId) {
        Notice mine = service.enabled(guildId) ? service.current(guildId, userId).orElse(null) : null;
        LocalDate today = AbsenceService.today();
        return DataObject.empty().put("enabled", service.enabled(guildId))
                .put("today", today.toString()).put("latest", today.plusDays(AbsenceRules.MAX_DAYS).toString()).put("maxReason", AbsenceRules.MAX_REASON)
                .put("notice", mine == null ? null : DataObject.empty().put("until", mine.until().toString()).put("reason", mine.reason()));
    }
}
