package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.clanprofile.ClanProfileService;
import com.younglings.bot.clanprofile.ProfileRules;
import com.younglings.bot.clanprofile.PublicEvents;
import com.younglings.bot.clanprofile.PublicEvents.EventInfo;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Which of a server's Discord events are shown publicly: {@code GET clan-events} (the upcoming ones, each with whether it is shared and
 * whether sharing would actually show it) and {@code PUT clan-events/{id}} {@code {scope}} (NONE, ALLIANCE or PUBLIC). Sharing an event only matters once the
 * clan's listing is public or unlisted; until then it is remembered but nobody sees it. Reached only through {@link TicketAdminApi},
 * which has already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class ClanEventsAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(ClanEventsAdminApi.class);

    private final ClanProfileRepository repository;
    private final ClanProfileService service;

    public ClanEventsAdminApi(ClanProfileRepository repository, ClanProfileService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("clan-events")) return null;
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return list(guild);
        }
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "PUT");
            return setShared(guild, actor, parts[1], body.get());
        }
        return null;
    }

    DataObject list(Guild guild) {
        List<EventInfo> events = DirectoryApi.eventsOf(guild);
        Set<Long> existing = new HashSet<>();
        for (EventInfo e : events) existing.add(e.id());
        repository.forgetSharedExcept(guild.getIdLong(), existing);
        java.util.Map<Long, String> scopes = repository.eventScopes(guild.getIdLong());

        Instant now = Instant.now();
        DataArray array = DataArray.empty();
        events.stream().filter(e -> PublicEvents.upcoming(e, now)).sorted(Comparator.comparing(EventInfo::start)).forEach(e -> array.add(DataObject.empty()
                .put("id", Long.toString(e.id())).put("name", e.name()).put("location", e.location()).put("status", e.status())
                .put("startTime", e.start().toString()).put("endTime", e.end() == null ? null : e.end().toString()).put("shared", "PUBLIC".equals(scopes.get(e.id())))
                .put("scope", scopes.getOrDefault(e.id(), "NONE"))));

        ClanProfile profile = service.profileOf(guild.getIdLong());
        ProfileRules.Listing listing = ProfileRules.listing(profile, service.clanNameOf(guild.getIdLong()));
        return DataObject.empty().put("events", array).put("listing", listing.name()).put("slug", profile.slug());
    }

    DataObject setShared(Guild guild, Member actor, String rawId, DataObject body) {
        if (rawId == null || !rawId.strip().matches("\\d{1,20}")) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_valid_event"));
        long id = Long.parseLong(rawId.strip());
        // `scope` (NONE, ALLIANCE or PUBLIC) is the full form; `shared` (true = PUBLIC, false = NONE) is kept for older callers.
        String scope;
        if (body.hasKey("scope")) {
            scope = body.getString("scope", "").strip().toUpperCase(java.util.Locale.ROOT);
            if (!scope.equals("NONE") && !scope.equals("ALLIANCE") && !scope.equals("PUBLIC")) throw new ApiError(400, Text.of(guild, "dashboard.choose_who_may_see_the_event"));
        } else if (body.hasKey("shared")) {
            scope = body.getBoolean("shared", false) ? "PUBLIC" : "NONE";
        } else {
            throw new ApiError(400, Text.of(guild, "dashboard.say_who_may_see_the_event"));
        }
        if (DirectoryApi.eventsOf(guild).stream().noneMatch(e -> e.id() == id)) throw new ApiError(404, Text.of(guild, "dashboard.that_event_doesn_t_exist_in"));
        repository.setEventScope(guild.getIdLong(), id, scope.equals("NONE") ? null : scope, actor.getIdLong());
        log.info("Dashboard: {} set event {} to {}", actor.getId(), id, scope);
        return list(guild);
    }
}
