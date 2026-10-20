package com.younglings.bot.internal;

import com.younglings.bot.alliance.AllianceRepository;
import com.younglings.bot.alliance.AllianceRules;
import com.younglings.bot.events.EventCohostRepository;
import com.younglings.bot.events.EventCohostRepository.Cohost;
import com.younglings.bot.events.EventCohostRepository.Status;
import com.younglings.bot.events.EventProblem;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRepository.Participant;
import com.younglings.bot.events.EventRules;
import com.younglings.bot.events.EventService;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Co-hosted events (T-219, design {@code docs/EVENTS-CROSS-SERVER.md} sections 2, 3, 6 and 7). Reached through {@link TicketAdminApi}, which checks the asker
 * against the calling server and audit-logs every change; every route here then checks the calling server's place in the event.
 * <ul>
 *   <li>The host: {@code GET events/{id}/cohosts}, {@code POST events/{id}/cohosts {guildId}} (invite a server it shares an active alliance with),
 *       {@code DELETE events/{id}/cohosts/{guildId}} (only before signups close).</li>
 *   <li>A co-host: {@code GET cohost-events} (invitations and the events it co-hosts, with its own players only), {@code POST cohost-events/{id}/accept},
 *       {@code POST cohost-events/{id}/decline}, {@code DELETE cohost-events/{id}/players/{userId}} (one of its own players, before the roster is confirmed).</li>
 * </ul>
 * A co-host never sees another server's players: only counts and its own.
 */
@BService
public class EventCohostApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(EventCohostApi.class);
    /** At most this many servers invited to or co-hosting one event. */
    public static final int MAX_COHOSTS = 10;

    private final EventCohostRepository cohosts;
    private final EventRepository events;
    private final EventService service;
    private final AllianceRepository alliances;

    public EventCohostApi(EventCohostRepository cohosts, EventRepository events, EventService service, AllianceRepository alliances) {
        this.cohosts = cohosts;
        this.events = events;
        this.service = service;
        this.alliances = alliances;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        try {
            if (parts.length >= 3 && parts[0].equals("events") && parts[2].equals("cohosts")) return hostRoute(guild, actor, method, parts, body);
            if (parts.length >= 1 && parts[0].equals("cohost-events")) return cohostRoute(guild, actor, method, parts);
            return null;
        } catch (EventProblem e) {
            throw new ApiError(e.status() == 500 ? 500 : e.status(), e.getMessage(), e.problems());
        }
    }

    // ---------- the host ----------

    private DataObject hostRoute(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        Event event = service.event(guild.getIdLong(), idOf(parts[1], "event")); // the event must be this server's
        if (parts.length == 3) {
            if (method.equals("GET")) return hostView(guild, event);
            AdminRouteExtension.requireMethod(method, "POST");
            long target = idOf(body.get().getString("guildId", ""), "server");
            invite(guild, actor, event, target);
            return hostView(guild, event);
        }
        if (parts.length == 4) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            long target = idOf(parts[3], "server");
            remove(actor, event, target);
            return hostView(guild, event);
        }
        return null;
    }

    void invite(Guild guild, Member actor, Event event, long target) {
        if (event.status() == EventRules.Status.DONE || event.status() == EventRules.Status.CANCELLED) throw new EventProblem(409, "This event is over.");
        if (target == event.guildId()) throw new EventProblem(400, "Your own server is the host.");
        if (!allied(event.guildId(), target)) throw new EventProblem(403, "You can only invite a server that is in an alliance with yours (both accepted).");
        long active = cohosts.forEvent(event.id()).stream().filter(c -> c.status() == Status.INVITED || c.status() == Status.ACCEPTED).count();
        boolean already = cohosts.get(event.id(), target).map(c -> c.status() == Status.INVITED || c.status() == Status.ACCEPTED).orElse(false);
        if (already) throw new EventProblem(409, "That server is already invited or co-hosting.");
        if (active >= MAX_COHOSTS) throw new EventProblem(409, "An event can have at most " + MAX_COHOSTS + " co-hosts.");
        cohosts.invite(event.id(), target, actor.getIdLong());
        log.info("Dashboard: {} invited server {} to co-host event {}", actor.getId(), target, event.id());
    }

    void remove(Member actor, Event event, long target) {
        if (events.setup(event.id()).signupsClosed()) throw new EventProblem(409, "Signups have closed, so the co-hosts stay. Remove their players by hand on the Teams and players tab if you need to.");
        Cohost c = cohosts.get(event.id(), target).orElseThrow(() -> new EventProblem(404, "That server isn't a co-host of this event."));
        if (!cohosts.decide(event.id(), target, c.status(), Status.REMOVED, actor.getIdLong()) || c.status() == Status.REMOVED || c.status() == Status.DECLINED) {
            throw new EventProblem(409, "That server isn't invited or co-hosting any more.");
        }
        log.info("Dashboard: {} removed server {} as a co-host of event {}", actor.getId(), target, event.id());
    }

    private DataObject hostView(Guild guild, Event event) {
        List<Participant> players = events.participants(event.id());
        DataArray list = DataArray.empty();
        Set<Long> taken = new LinkedHashSet<>();
        for (Cohost c : cohosts.forEvent(event.id())) {
            if (c.status() == Status.REMOVED) continue;
            taken.add(c.guildId());
            long count = players.stream().filter(p -> p.guildId() == c.guildId()).count();
            list.add(DataObject.empty().put("guildId", Long.toString(c.guildId())).put("name", nameOf(guild, c.guildId())).put("status", c.status().name()).put("players", count));
        }
        DataArray candidates = DataArray.empty();
        for (long id : alliedServers(event.guildId())) {
            if (!taken.contains(id) || cohosts.get(event.id(), id).map(c -> c.status() == Status.DECLINED).orElse(false)) {
                candidates.add(DataObject.empty().put("guildId", Long.toString(id)).put("name", nameOf(guild, id)));
            }
        }
        return DataObject.empty().put("cohosts", list).put("candidates", candidates).put("max", MAX_COHOSTS)
                .put("canRemove", !events.setup(event.id()).signupsClosed());
    }

    // ---------- a co-host ----------

    private DataObject cohostRoute(Guild guild, Member actor, String method, String[] parts) {
        long me = guild.getIdLong();
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return cohostView(guild);
        }
        long eventId = idOf(parts[1], "event");
        Cohost mine = cohosts.get(eventId, me).orElseThrow(() -> new ApiError(404, "Your server isn't invited to that event."));
        Event event = events.eventById(eventId).orElseThrow(() -> new ApiError(404, "That event doesn't exist any more."));
        if (parts.length == 3 && (parts[2].equals("accept") || parts[2].equals("decline"))) {
            AdminRouteExtension.requireMethod(method, "POST");
            if (mine.status() != Status.INVITED) throw new ApiError(409, "That invitation isn't open any more.");
            if (event.status() == EventRules.Status.DONE || event.status() == EventRules.Status.CANCELLED) throw new ApiError(409, "That event is over.");
            Status to = parts[2].equals("accept") ? Status.ACCEPTED : Status.DECLINED;
            if (to == Status.ACCEPTED && !allied(event.guildId(), me)) throw new ApiError(403, "Your server is no longer in an alliance with the host.");
            if (!cohosts.decide(eventId, me, Status.INVITED, to, actor.getIdLong())) throw new ApiError(409, "That invitation isn't open any more.");
            log.info("Dashboard: {} {} co-hosting event {} for server {}", actor.getId(), to == Status.ACCEPTED ? "accepted" : "declined", eventId, me);
            return cohostView(guild);
        }
        if (parts.length == 4 && parts[2].equals("players")) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            if (mine.status() != Status.ACCEPTED) throw new ApiError(403, "Your server doesn't co-host that event.");
            long userId = idOf(parts[3], "player");
            Participant p = events.participant(eventId, userId).orElseThrow(() -> new ApiError(404, "That player isn't in the event."));
            if (p.guildId() != me) throw new ApiError(403, "You can only remove players who joined through your server.");
            if (events.setup(eventId).rosterConfirmed() || event.status() != EventRules.Status.SIGNUPS) throw new ApiError(409, "The host has confirmed the roster, so only they can change it now.");
            service.leave(event.guildId(), eventId, userId);
            log.info("Dashboard: {} (co-host server {}) removed player {} from event {}", actor.getId(), me, userId, eventId);
            return cohostView(guild);
        }
        return null;
    }

    private DataObject cohostView(Guild guild) {
        long me = guild.getIdLong();
        DataArray invitations = DataArray.empty();
        DataArray hosting = DataArray.empty();
        for (Cohost c : cohosts.forGuild(me)) {
            Event e = events.eventById(c.eventId()).orElse(null);
            if (e == null) continue;
            DataObject base = DataObject.empty().put("eventId", Long.toString(e.id())).put("name", e.name()).put("host", nameOf(guild, e.guildId())).put("status", e.status().name())
                    .put("startsAt", e.startsAt() == null ? null : e.startsAt().toString());
            if (c.status() == Status.INVITED) {
                invitations.add(base);
                continue;
            }
            List<Participant> players = events.participants(e.id());
            DataArray yours = DataArray.empty();
            for (Participant p : players) {
                if (p.guildId() != me) continue;
                Member m = guild.getMemberById(p.userId());
                yours.add(DataObject.empty().put("userId", Long.toString(p.userId())).put("name", m == null ? "A member who left" : m.getEffectiveName()).put("waiting", p.waiting()));
            }
            boolean canRemove = e.status() == EventRules.Status.SIGNUPS && !events.setup(e.id()).rosterConfirmed();
            hosting.add(base.put("players", players.stream().filter(p -> !p.waiting()).count()).put("yours", yours).put("canRemove", canRemove));
        }
        return DataObject.empty().put("invitations", invitations).put("events", hosting);
    }

    // ---------- alliances ----------

    /** Whether the two servers are both accepted members of one alliance. */
    boolean allied(long a, long b) {
        return alliedServers(a).contains(b);
    }

    /** Every server that shares an alliance with {@code host}, both accepted. */
    Set<Long> alliedServers(long host) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AllianceRepository.Alliance alliance : alliances.alliancesOf(host)) {
            List<AllianceRepository.Membership> members = alliances.membersOf(alliance.id());
            boolean hostActive = members.stream().anyMatch(m -> m.guildId() == host && m.status() == AllianceRules.Status.ACTIVE);
            if (!hostActive) continue;
            members.stream().filter(m -> m.guildId() != host && m.status() == AllianceRules.Status.ACTIVE).forEach(m -> ids.add(m.guildId()));
        }
        return ids;
    }

    private static String nameOf(Guild any, long guildId) {
        Guild g = any.getJDA().getGuildById(guildId);
        return g == null ? "A server JonnyBot has left" : g.getName();
    }

    private static long idOf(String raw, String what) {
        if (raw == null || !raw.strip().matches("\\d{1,20}")) throw new ApiError(400, "That isn't a valid " + what + ".");
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new ApiError(400, "That isn't a valid " + what + ".");
        }
    }
}
