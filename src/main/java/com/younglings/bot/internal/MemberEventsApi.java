package com.younglings.bot.internal;

import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRepository.Participant;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.EventScoring.Standing;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.EventSettings;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * What a member sees of the events they are in, for their profile on the website ({@code GET /internal/me/events}): their place and
 * points, which objectives they have completed, whether their buy-in is paid, pending proof, and their prizes with where each stands.
 * Only the member's own rows are returned (other players' prizes and claims never are), and drafts and cancelled-before-start events
 * are left out. Also lists events taking signups that the member hasn't joined, so they can find them.
 */
@BService
public class MemberEventsApi {
    private static final Logger log = LoggerFactory.getLogger(MemberEventsApi.class);
    private static final int MAX_EVENTS = 30;

    private final EventService service;
    private final EventRepository repository;

    public MemberEventsApi(EventService service, EventRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    public DataObject mine(Guild guild, long userId) {
        DataArray mine = DataArray.empty();
        DataArray open = DataArray.empty();
        // only people in the server see its events
        if (guild.getMemberById(userId) == null) return DataObject.empty().put("events", mine).put("open", open);

        long guildId = guild.getIdLong();
        List<Event> joined = repository.eventsForUser(guildId, userId, MAX_EVENTS);
        for (Event event : joined) {
            try {
                mine.add(one(guildId, event, userId));
            } catch (RuntimeException e) {
                log.warn("Could not build member view of event {}", event.id(), e);
            }
        }
        for (Event event : repository.events(guildId, List.of(Status.SIGNUPS), 20)) {
            if (joined.stream().anyMatch(j -> j.id() == event.id())) continue;
            EventSettings settings = settingsOf(event);
            open.add(DataObject.empty().put("id", Long.toString(event.id())).put("name", event.name()).put("description", event.description())
                    .put("template", event.template()).put("scoringMode", event.scoringMode().name())
                    .put("signupClosesAt", settings.signupClosesAt() == null ? null : settings.signupClosesAt().toString())
                    .put("buyIn", settings.buyIn().enabled() ? settings.buyIn().amount() : 0));
        }
        return DataObject.empty().put("events", mine).put("open", open);
    }

    private static EventSettings settingsOf(Event event) {
        try {
            return EventSettings.parse(DataObject.fromJson(event.config()));
        } catch (RuntimeException e) {
            return EventSettings.defaults();
        }
    }

    private DataObject one(long guildId, Event event, long userId) {
        Participant me = repository.participant(event.id(), userId).orElseThrow();
        EventSettings settings = settingsOf(event);
        String teamName = me.teamId() == null ? null : repository.teams(event.id()).stream().filter(t -> t.id() == me.teamId()).map(EventRepository.Team::name).findFirst().orElse(null);

        boolean scored = event.status() == Status.RUNNING || event.status() == Status.SCORING || event.status() == Status.DONE;
        List<Standing> standings = scored ? service.standings(guildId, event.id()) : List.of();
        String subject = event.scoringMode() == com.younglings.bot.events.EventRules.ScoringMode.TEAM ? (me.teamId() == null ? null : "t:" + me.teamId()) : "u:" + userId;
        int rank = 0;
        Standing mineStanding = null;
        for (int i = 0; i < standings.size(); i++) {
            if (standings.get(i).subject().equals(subject)) {
                rank = i + 1;
                mineStanding = standings.get(i);
            }
        }

        DataArray objectives = DataArray.empty();
        if (scored && !me.waiting()) {
            for (var o : service.objectives(event.id())) {
                int done = mineStanding == null ? 0 : mineStanding.completions().getOrDefault(o.key(), 0);
                objectives.add(DataObject.empty().put("key", o.key()).put("label", o.label()).put("points", o.points()).put("target", o.target())
                        .put("repeatLimit", o.repeatLimit()).put("done", done));
            }
        }

        DataArray awards = DataArray.empty();
        if (event.status() == Status.SCORING || event.status() == Status.DONE) {
            service.awards(guildId, event.id()).stream().filter(a -> a.userId() == userId).forEach(a -> awards.add(DataObject.empty()
                    .put("place", a.place()).put("kind", a.kind().name()).put("description", a.description()).put("amount", a.amount()).put("status", a.status().name())));
        }

        return DataObject.empty().put("id", Long.toString(event.id())).put("name", event.name()).put("description", event.description()).put("template", event.template())
                .put("status", event.status().name()).put("scoringMode", event.scoringMode().name())
                .put("waiting", me.waiting()).put("team", teamName)
                .put("buyIn", DataObject.empty().put("enabled", settings.buyIn().enabled()).put("amount", settings.buyIn().amount()).put("status", me.buyIn().name()))
                .put("rank", rank).put("of", standings.size()).put("points", mineStanding == null ? 0 : mineStanding.points())
                .put("objectives", objectives).put("pendingProof", repository.pendingClaimsFor(event.id(), userId)).put("awards", awards);
    }
}
