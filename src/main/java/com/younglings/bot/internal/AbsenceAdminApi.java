package com.younglings.bot.internal;

import com.younglings.bot.absence.AbsenceRepository.Notice;
import com.younglings.bot.absence.AbsenceRules;
import com.younglings.bot.absence.AbsenceService;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * The dashboard's Leave of absence page (T-190): the on/off switch and who is away, with an "end early" for staff. GET/PUT
 * {@code absence} ({@code {enabled}}), DELETE {@code absence/<userId>}. Reached only through {@link TicketAdminApi}, which has already checked
 * the asker may use the dashboard here and audit-logs every change.
 */
@BService
public class AbsenceAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(AbsenceAdminApi.class);

    private final AbsenceService service;

    public AbsenceAdminApi(AbsenceService service) {
        this.service = service;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length == 0 || !parts[0].equals("absence")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return view(guild);
            AdminRouteExtension.requireMethod(method, "PUT");
            boolean on = body.get().getBoolean("enabled", false);
            try {
                service.setEnabled(guild.getIdLong(), on);
            } catch (SQLException e) {
                log.warn("Could not save the absence switch for {}", guild.getId(), e);
                throw new ApiError(500, "That didn't save. Try again in a moment.");
            }
            log.info("Dashboard: {} switched leave of absence {} in {}", actor.getId(), on ? "on" : "off", guild.getId());
            return view(guild);
        }
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            long userId;
            try {
                userId = Long.parseUnsignedLong(parts[1]);
            } catch (NumberFormatException e) {
                throw new ApiError(400, "That isn't a member.");
            }
            boolean was = service.end(guild.getIdLong(), userId);
            if (was) log.info("Dashboard: {} ended {}'s leave of absence in {}", actor.getId(), userId, guild.getId());
            return view(guild);
        }
        return null;
    }

    DataObject view(Guild guild) {
        DataArray notices = DataArray.empty();
        for (Notice n : service.active(guild.getIdLong())) {
            Member m = guild.getMemberById(n.userId());
            notices.add(DataObject.empty().put("userId", Long.toString(n.userId())).put("name", m == null ? null : m.getEffectiveName())
                    .put("until", n.until().toString()).put("reason", n.reason()).put("since", n.createdAt() == null ? null : n.createdAt().toString()));
        }
        return DataObject.empty().put("enabled", service.enabled(guild.getIdLong())).put("maxDays", AbsenceRules.MAX_DAYS).put("notices", notices);
    }
}
