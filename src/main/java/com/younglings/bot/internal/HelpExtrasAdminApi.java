package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.ticket.HelpExtras;
import com.younglings.bot.ticket.HelpExtrasRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * The optional extras of the help tickets for the website (route {@code help-extras}), shown on the PvM Help page. A save is checked whole before anything is
 * stored. Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server.
 */
@BService
public class HelpExtrasAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(HelpExtrasAdminApi.class);

    private final HelpExtrasRepository repository;

    public HelpExtrasAdminApi(HelpExtrasRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("help-extras")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        HelpExtras e = repository.get(guild.getIdLong());
        return DataObject.empty().put("eventButton", e.eventButton()).put("eventStartHours", e.eventStartHours()).put("eventDays", e.eventDays())
                .put("maxStartHours", HelpExtras.MAX_START_HOURS).put("maxDays", HelpExtras.MAX_DAYS);
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        HelpExtras current = repository.get(guild.getIdLong());
        HelpExtras next = new HelpExtras(guild.getIdLong(),
                body.hasKey("eventButton") ? body.getBoolean("eventButton", false) : current.eventButton(),
                body.hasKey("eventStartHours") ? body.getInt("eventStartHours", -1) : current.eventStartHours(),
                body.hasKey("eventDays") ? body.getInt("eventDays", -1) : current.eventDays());
        var problems = next.problems();
        if (!problems.isEmpty()) throw new ApiError(400, "Those settings can't be saved yet.", problems);
        repository.save(next);
        log.info("Dashboard: {} saved the help ticket extras (event button {})", actor.getId(), next.eventButton() ? "on" : "off");
        return get(guild);
    }
}
