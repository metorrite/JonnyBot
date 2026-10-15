package com.younglings.bot.internal;

import com.younglings.bot.events.EventProblem;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRepository.Claim;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules;
import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Source;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.BingoTemplate;
import com.younglings.bot.events.EventBoardPanels;
import com.younglings.bot.events.EventBonuses;
import com.younglings.bot.events.EventScoring.Standing;
import com.younglings.bot.events.EventSignups;
import com.younglings.bot.events.EventSettings;
import com.younglings.bot.events.PrizePlan;
import com.younglings.bot.events.PrizeRules;
import com.younglings.bot.events.PrizeRules.AwardStatus;
import com.younglings.bot.events.PrizeRules.BuyInStatus;
import com.younglings.bot.events.PrizeRules.PrizeKind;
import com.younglings.bot.events.TeamRules;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.EventService.EventInput;
import com.younglings.bot.events.EventTemplates;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The event engine for the website. Events: {@code GET/POST events}, {@code GET events/templates}, {@code GET/PUT/DELETE events/{id}},
 * {@code POST events/{id}/status}. Inside an event: {@code PUT .../objectives} (the whole list), {@code POST .../teams},
 * {@code DELETE .../teams/{teamId}}, {@code POST .../participants}, {@code PUT/DELETE .../participants/{userId}}, {@code POST .../progress},
 * {@code GET .../standings}, {@code GET/POST .../claims} and {@code POST .../claims/{claimId}/approve|deny}. Everything is checked by
 * {@link EventService}. Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server
 * and audit-logs every change.
 */
@BService
public class EventsAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(EventsAdminApi.class);

    private final EventService service;
    private final EventRepository repository;
    private final EventTemplates templates;
    private final EventSignups signups;
    private final BingoTemplate bingo;
    private final EventBoardPanels panels;

    public EventsAdminApi(EventService service, EventRepository repository, EventTemplates templates, EventSignups signups, BingoTemplate bingo, EventBoardPanels panels) {
        this.service = service;
        this.repository = repository;
        this.templates = templates;
        this.signups = signups;
        this.bingo = bingo;
        this.panels = panels;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("events")) return null;
        try {
            return dispatch(guild, actor, method, parts, body);
        } catch (EventProblem e) {
            throw new ApiError(e.status() == 500 ? 500 : e.status(), e.getMessage(), e.problems());
        }
    }

    private DataObject dispatch(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length == 1) {
            if (method.equals("GET")) return list(guild);
            AdminRouteExtension.requireMethod(method, "POST");
            return create(guild, actor, body.get());
        }
        if (parts.length == 2 && parts[1].equals("templates")) {
            AdminRouteExtension.requireMethod(method, "GET");
            return templateList();
        }
        long id = idOf(parts[1], "event");
        if (parts.length == 2) {
            return switch (method) {
                case "GET" -> detail(guild, id);
                case "PUT" -> update(guild, actor, id, body.get());
                case "DELETE" -> delete(guild, actor, id);
                default -> throw new ApiError(405, "Method not allowed");
            };
        }
        String what = parts[2];
        if (parts.length == 3) {
            switch (what) {
                case "status" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    return moveStage(guild, actor, id, body.get());
                }
                case "objectives" -> {
                    AdminRouteExtension.requireMethod(method, "PUT");
                    return setObjectives(guild, actor, id, body.get());
                }
                case "teams" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    return addTeam(guild, actor, id, body.get());
                }
                case "participants" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    return addParticipant(guild, actor, id, body.get());
                }
                case "progress" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    return progress(guild, actor, id, body.get());
                }
                case "bingo" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    return rerollBingo(guild, actor, id, body.get());
                }
                case "board" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    service.event(guild.getIdLong(), id);
                    return DataObject.empty().put("message", panels.refresh(guild.getIdLong(), id));
                }
                case "signup" -> {
                    AdminRouteExtension.requireMethod(method, "POST");
                    signups.open(guild, id, actor.getIdLong());
                    log.info("Dashboard: {} opened the signup of event {}", actor.getId(), id);
                    return detail(guild, id);
                }
                case "prizes" -> {
                    AdminRouteExtension.requireMethod(method, "PUT");
                    return setPrizes(guild, actor, id, body.get());
                }
                case "awards" -> {
                    if (method.equals("GET")) return awards(guild, id);
                    AdminRouteExtension.requireMethod(method, "POST");
                    service.generateAwards(guild.getIdLong(), id);
                    log.info("Dashboard: {} worked out the awards for event {}", actor.getId(), id);
                    return awards(guild, id);
                }
                case "standings" -> {
                    AdminRouteExtension.requireMethod(method, "GET");
                    return standings(guild, id);
                }
                case "claims" -> {
                    if (method.equals("GET")) return claims(guild, id);
                    AdminRouteExtension.requireMethod(method, "POST");
                    return submitClaim(guild, actor, id, body.get());
                }
                default -> {
                    return null;
                }
            }
        }
        if (parts.length == 4 && what.equals("signup") && parts[3].equals("sync")) {
            AdminRouteExtension.requireMethod(method, "POST");
            int added = signups.sync(guild, id);
            log.info("Dashboard: {} synced the signup of event {} ({} added)", actor.getId(), id, added);
            return detail(guild, id).put("synced", added);
        }
        if (parts.length == 4 && what.equals("teams")) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            service.removeTeam(guild.getIdLong(), id, idOf(parts[3], "team"));
            log.info("Dashboard: {} removed a team from event {}", actor.getId(), id);
            return detail(guild, id);
        }
        if (parts.length == 4 && what.equals("participants")) {
            long userId = idOf(parts[3], "player");
            if (method.equals("DELETE")) {
                service.leave(guild.getIdLong(), id, userId);
                return detail(guild, id);
            }
            AdminRouteExtension.requireMethod(method, "PUT");
            service.setTeam(guild.getIdLong(), id, userId, optionalId(body.get(), "teamId"));
            return detail(guild, id);
        }
        if (parts.length == 5 && what.equals("participants") && parts[4].equals("buyin")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            BuyInStatus to;
            try {
                to = BuyInStatus.valueOf(text(body.get(), "status").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ApiError(400, "Choose a buy-in stage: due, paid or refunded.");
            }
            service.setBuyIn(guild.getIdLong(), id, idOf(parts[3], "player"), to);
            log.info("Dashboard: {} set a buy-in to {} in event {}", actor.getId(), to, id);
            return detail(guild, id);
        }
        if (parts.length == 5 && what.equals("awards") && parts[4].equals("status")) {
            AdminRouteExtension.requireMethod(method, "POST");
            DataObject b = body.get();
            AwardStatus to;
            try {
                to = AwardStatus.valueOf(text(b, "status").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ApiError(400, "Choose an award stage: paid, revoked or refunded.");
            }
            service.moveAward(guild.getIdLong(), id, idOf(parts[3], "award"), to, actor.getIdLong(), text(b, "note"));
            log.info("Dashboard: {} moved an award to {} in event {}", actor.getId(), to, id);
            return awards(guild, id);
        }
        if (parts.length == 5 && what.equals("claims") && (parts[4].equals("approve") || parts[4].equals("deny"))) {
            AdminRouteExtension.requireMethod(method, "POST");
            boolean approve = parts[4].equals("approve");
            DataObject b = body.get();
            service.decideClaim(guild.getIdLong(), id, idOf(parts[3], "claim"), approve, text(b, "note"), actor.getIdLong());
            log.info("Dashboard: {} {} a claim in event {}", actor.getId(), approve ? "approved" : "denied", id);
            return claims(guild, id);
        }
        return null;
    }

    // ---------- events ----------

    DataObject list(Guild guild) {
        DataArray events = DataArray.empty();
        for (Event e : repository.events(guild.getIdLong(), List.of(), 100)) events.add(eventJson(e));
        return DataObject.empty().put("events", events).put("maxOpen", EventService.MAX_OPEN_EVENTS);
    }

    DataObject templateList() {
        DataArray array = DataArray.empty();
        templates.all().forEach(t -> array.add(DataObject.empty().put("key", t.key()).put("label", t.label()).put("description", t.description())));
        return DataObject.empty().put("templates", array);
    }

    DataObject create(Guild guild, Member actor, DataObject body) {
        long id = service.createEvent(guild.getIdLong(), actor.getIdLong(), input(body, null));
        log.info("Dashboard: {} created event {}", actor.getId(), id);
        return detail(guild, id);
    }

    DataObject update(Guild guild, Member actor, long id, DataObject body) {
        Event existing = service.event(guild.getIdLong(), id);
        service.updateEvent(guild.getIdLong(), id, input(body, existing));
        log.info("Dashboard: {} changed event {}", actor.getId(), id);
        return detail(guild, id);
    }

    DataObject delete(Guild guild, Member actor, long id) {
        service.deleteDraft(guild.getIdLong(), id);
        log.info("Dashboard: {} deleted draft event {}", actor.getId(), id);
        return list(guild);
    }

    DataObject moveStage(Guild guild, Member actor, long id, DataObject body) {
        Status to;
        try {
            to = Status.valueOf(text(body, "status").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "Choose a stage: draft, signups, running, scoring, done or cancelled.");
        }
        service.moveStage(guild.getIdLong(), id, to);
        log.info("Dashboard: {} moved event {} to {}", actor.getId(), id, to);
        String warning = null;
        try {
            // opening signups posts the event's signup when channels were chosen; starting or ending the event closes it
            if (to == Status.SIGNUPS && signups.stateOf(service.event(guild.getIdLong(), id)).channelsChosen() && !signups.stateOf(service.event(guild.getIdLong(), id)).attached()) signups.open(guild, id, actor.getIdLong());
            signups.onStage(guild, id, to);
        } catch (EventProblem e) {
            warning = e.getMessage();
            log.warn("Event {} moved to {} but its signup wasn't updated: {}", id, to, e.getMessage());
        }
        DataObject detail = detail(guild, id);
        return warning == null ? detail : detail.put("signupWarning", warning);
    }

    DataObject detail(Guild guild, long id) {
        Event e = service.event(guild.getIdLong(), id);
        DataArray objectives = DataArray.empty();
        for (ObjectiveSpec o : service.objectives(id)) objectives.add(objectiveJson(o));
        DataArray teams = DataArray.empty();
        repository.teams(id).forEach(t -> teams.add(DataObject.empty().put("id", Long.toString(t.id())).put("name", t.name()).put("captainId", t.captainId() == null ? null : Long.toString(t.captainId()))));
        DataArray players = DataArray.empty();
        repository.participants(id).forEach(p -> players.add(DataObject.empty().put("userId", Long.toString(p.userId())).put("userName", name(guild, p.userId()))
                .put("guildId", Long.toString(p.guildId())).put("teamId", p.teamId() == null ? null : Long.toString(p.teamId())).put("joinedAt", p.joinedAt().toString())
                .put("kind", p.kind().name()).put("waiting", p.waiting()).put("buyIn", p.buyIn().name())));
        DataArray next = DataArray.empty();
        EventRules.next(e.status()).stream().sorted().forEach(s -> next.add(s.name()));
        EventSettings settings = EventSettings.parse(eventJson(e).getObject("config"));
        DataArray prizes = DataArray.empty();
        service.prizes(id).forEach(p -> prizes.add(DataObject.empty().put("id", Long.toString(p.id())).put("place", p.place()).put("placeTo", p.placeTo()).put("kind", p.kind().name())
                .put("description", p.description()).put("amount", p.amount()).put("percentOfPot", p.percentOfPot()).put("split", p.split().name())));
        var signup = signups.stateOf(e);
        DataArray refused = DataArray.empty();
        repository.refusals(id).forEach(r -> refused.add(DataObject.empty().put("userId", Long.toString(r.userId())).put("userName", name(guild, r.userId()))
                .put("reason", r.reason()).put("at", r.at().toString())));
        return DataObject.empty().put("refused", refused).put("event", eventJson(e)).put("signup", DataObject.empty().put("attached", signup.attached()).put("active", signup.active())
                        .put("entries", signup.entries()).put("channelsChosen", signup.channelsChosen())).put("prizes", prizes).put("pot", service.pot(guild.getIdLong(), id)).put("potVisible", PrizeRules.potVisible(settings.buyIn()))
                .put("buyInEnabled", settings.buyIn().enabled()).put("objectives", objectives).put("teams", teams).put("participants", players).put("nextStages", next)
                .put("editable", EventRules.editable(e.status()));
    }

    // ---------- objectives, teams, players ----------

    DataObject setObjectives(Guild guild, Member actor, long id, DataObject body) {
        List<ObjectiveSpec> specs = new ArrayList<>();
        DataArray array = body.hasKey("objectives") && !body.isNull("objectives") ? body.getArray("objectives") : DataArray.empty();
        for (int i = 0; i < array.length(); i++) {
            DataObject o = array.getObject(i);
            String label = text(o, "label");
            String key = text(o, "key");
            Evidence evidence;
            try {
                evidence = Evidence.valueOf(text(o, "evidence").isEmpty() ? "EITHER" : text(o, "evidence").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ApiError(400, "\"" + label + "\": choose how progress is recorded (auto, proof, either or organiser).");
            }
            List<String> prerequisites = new ArrayList<>();
            if (o.hasKey("prerequisites") && !o.isNull("prerequisites")) {
                DataArray p = o.getArray("prerequisites");
                for (int j = 0; j < p.length(); j++) prerequisites.add(p.getString(j));
            }
            specs.add(new ObjectiveSpec(key.isEmpty() ? EventRules.keyFrom(label) : key, label, text(o, "description"), number(o, "points", 0), evidence,
                    number(o, "target", 1), (int) number(o, "repeatLimit", 1), prerequisites));
        }
        service.setObjectives(guild.getIdLong(), id, specs);
        log.info("Dashboard: {} saved {} objectives for event {}", actor.getId(), specs.size(), id);
        return detail(guild, id);
    }

    DataObject addTeam(Guild guild, Member actor, long id, DataObject body) {
        Long captain = optionalId(body, "captainId");
        service.addTeam(guild.getIdLong(), id, text(body, "name"), captain);
        log.info("Dashboard: {} added a team to event {}", actor.getId(), id);
        return detail(guild, id);
    }

    DataObject addParticipant(Guild guild, Member actor, long id, DataObject body) {
        Long userId = optionalId(body, "userId");
        if (userId == null) throw new ApiError(400, "Pick a player.");
        Member member = guild.getMemberById(userId);
        if (member == null || member.getUser().isBot()) throw new ApiError(400, "That player isn't a member of this server.");
        TeamRules.Kind kind = null;
        if (!text(body, "kind").isEmpty()) {
            try {
                kind = TeamRules.Kind.valueOf(text(body, "kind").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ApiError(400, "Choose the kind of player: home_clan, home_other or partner.");
            }
        }
        var joined = service.join(guild.getIdLong(), id, guild.getIdLong(), userId, optionalId(body, "teamId"), true, kind);
        if (joined.waiting()) log.info("Dashboard: {} put player {} on the waitlist of event {} at {}", actor.getId(), userId, id, joined.position());
        log.info("Dashboard: {} added player {} to event {}", actor.getId(), userId, id);
        return detail(guild, id);
    }

    // ---------- progress, claims, standings ----------

    DataObject progress(Guild guild, Member actor, long id, DataObject body) {
        Long userId = optionalId(body, "userId");
        if (userId == null) throw new ApiError(400, "Pick a player.");
        var recorded = service.recordProgress(guild.getIdLong(), id, text(body, "objective"), userId, number(body, "amount", 1), Source.MANUAL,
                text(body, "sourceKey").isEmpty() ? null : "manual:" + text(body, "sourceKey"), actor.getIdLong(), text(body, "note"));
        log.info("Dashboard: {} recorded progress in event {}", actor.getId(), id);
        return DataObject.empty().put("progressId", Long.toString(recorded.progressId())).put("total", recorded.total()).put("completions", recorded.completions())
                .put("standings", standings(guild, id).getArray("standings"));
    }

    DataObject standings(Guild guild, long id) {
        Event event = service.event(guild.getIdLong(), id);
        var teams = repository.teams(id);
        DataArray array = DataArray.empty();
        int rank = 1;
        for (Standing s : service.standings(guild.getIdLong(), id)) {
            boolean team = s.subject().startsWith("t:");
            long subjectId = Long.parseLong(s.subject().substring(2));
            String label = team ? teams.stream().filter(t -> t.id() == subjectId).map(EventRepository.Team::name).findFirst().orElse("A removed team") : name(guild, subjectId);
            DataObject completions = DataObject.empty();
            s.completions().forEach(completions::put);
            DataArray earned = DataArray.empty();
            EventBonuses.earned(service.bonusesOf(event), s.completions()).forEach(b -> earned.add(b.name()));
            array.add(DataObject.empty().put("rank", rank++).put("subject", s.subject()).put("kind", team ? "TEAM" : "PLAYER").put("label", label).put("points", s.points()).put("bonuses", earned)
                    .put("objectivesCompleted", s.objectivesCompleted()).put("completions", completions)
                    .put("lastCompletedAt", s.lastCompletedAt() == null ? null : s.lastCompletedAt().toString()));
        }
        return DataObject.empty().put("scoringMode", event.scoringMode().name()).put("standings", array);
    }

    DataObject claims(Guild guild, long id) {
        service.event(guild.getIdLong(), id);
        DataArray pending = DataArray.empty();
        repository.pendingClaims(id, 100).forEach(c -> pending.add(claimJson(guild, c)));
        DataArray decided = DataArray.empty();
        repository.decidedClaims(id, 50).forEach(c -> decided.add(claimJson(guild, c)));
        return DataObject.empty().put("pending", pending).put("decided", decided);
    }

    DataObject submitClaim(Guild guild, Member actor, long id, DataObject body) {
        Long userId = optionalId(body, "userId");
        if (userId == null) throw new ApiError(400, "Pick a player.");
        long claimId = service.submitClaim(guild.getIdLong(), id, text(body, "objective"), userId, number(body, "amount", 1), text(body, "note"), text(body, "proofUrl"));
        log.info("Dashboard: {} filed claim {} in event {}", actor.getId(), claimId, id);
        return claims(guild, id);
    }

    // ---------- bingo ----------

    DataObject rerollBingo(Guild guild, Member actor, long id, DataObject body) {
        Event event = service.event(guild.getIdLong(), id);
        if (!BingoTemplate.KEY.equals(event.template())) throw new ApiError(409, "This event isn't a bingo.");
        Set<String> locked = new HashSet<>();
        if (body.hasKey("lockedKeys") && !body.isNull("lockedKeys")) {
            DataArray keys = body.getArray("lockedKeys");
            for (int i = 0; i < keys.length(); i++) locked.add(keys.getString(i));
        }
        Integer size = body.hasKey("size") && !body.isNull("size") ? (int) number(body, "size", 0) : null;
        Long seed = body.hasKey("seed") && !body.isNull("seed") && !text(body, "seed").isEmpty() ? number(body, "seed", 0) : null;
        try {
            if (body.hasKey("replace") && !body.isNull("replace")) {
                DataObject r = body.getObject("replace");
                var replaced = bingo.replaceTile(DataObject.fromJson(event.config()), service.objectives(id), (int) number(r, "number", 0), text(r, "label"), number(r, "points", 0));
                service.reconfigure(guild.getIdLong(), id, replaced.config(), replaced.objectives());
                log.info("Dashboard: {} replaced tile {} of the bingo board of event {}", actor.getId(), number(r, "number", 0), id);
                return detail(guild, id);
            }
            var rerolled = bingo.reroll(DataObject.fromJson(event.config()), service.objectives(id), size, seed, locked);
            service.reconfigure(guild.getIdLong(), id, rerolled.config(), rerolled.objectives());
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, e.getMessage());
        }
        log.info("Dashboard: {} re-rolled the bingo board of event {}", actor.getId(), id);
        return detail(guild, id);
    }

    // ---------- prizes and awards ----------

    DataObject setPrizes(Guild guild, Member actor, long id, DataObject body) {
        List<PrizePlan.Prize> prizes = new ArrayList<>();
        DataArray array = body.hasKey("prizes") && !body.isNull("prizes") ? body.getArray("prizes") : DataArray.empty();
        for (int i = 0; i < array.length(); i++) {
            DataObject p = array.getObject(i);
            PrizeKind kind;
            PrizePlan.Split split;
            try {
                kind = PrizeKind.valueOf(text(p, "kind").isEmpty() ? "IN_GAME" : text(p, "kind").toUpperCase(Locale.ROOT));
                split = PrizePlan.Split.valueOf(text(p, "split").isEmpty() ? "EQUAL" : text(p, "split").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ApiError(400, "Choose the prize kind (in game, clan points or other) and how a team shares it (equal, each or captain).");
            }
            int place = (int) number(p, "place", 1);
            prizes.add(new PrizePlan.Prize(0, place, (int) number(p, "placeTo", place), kind, text(p, "description"), number(p, "amount", 0), (int) number(p, "percentOfPot", 0), split));
        }
        service.setPrizes(guild.getIdLong(), id, prizes);
        log.info("Dashboard: {} saved {} prizes for event {}", actor.getId(), prizes.size(), id);
        return detail(guild, id);
    }

    DataObject awards(Guild guild, long id) {
        DataArray array = DataArray.empty();
        for (EventRepository.AwardRow a : service.awards(guild.getIdLong(), id)) {
            DataArray history = DataArray.empty();
            service.awardHistory(guild.getIdLong(), id, a.id()).forEach(h -> history.add(DataObject.empty().put("from", h.from().name()).put("to", h.to().name())
                    .put("byName", h.by() == null ? null : name(guild, h.by())).put("note", h.note()).put("at", h.at().toString())));
            array.add(DataObject.empty().put("id", Long.toString(a.id())).put("place", a.place()).put("userId", Long.toString(a.userId())).put("userName", name(guild, a.userId()))
                    .put("kind", a.kind().name()).put("description", a.description()).put("amount", a.amount()).put("status", a.status().name()).put("history", history));
        }
        return DataObject.empty().put("awards", array);
    }

    // ---------- json ----------

    private EventInput input(DataObject body, Event existing) {
        Scope scope;
        ScoringMode mode;
        try {
            scope = body.hasKey("scope") ? Scope.valueOf(text(body, "scope").toUpperCase(Locale.ROOT)) : existing != null ? existing.scope() : Scope.PRIVATE;
            mode = body.hasKey("scoringMode") ? ScoringMode.valueOf(text(body, "scoringMode").toUpperCase(Locale.ROOT)) : existing != null ? existing.scoringMode() : ScoringMode.INDIVIDUAL;
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "Choose who may see the event (private, alliance or public) and how it is scored (individual or team).");
        }
        DataObject config = body.hasKey("config") && !body.isNull("config") ? body.getObject("config") : null;
        return new EventInput(body.hasKey("name") ? text(body, "name") : existing == null ? "" : existing.name(),
                body.hasKey("description") ? text(body, "description") : existing == null ? "" : existing.description(),
                existing != null ? existing.template() : text(body, "template").isEmpty() ? "custom" : text(body, "template"), scope, mode,
                instant(body, "startsAt", existing == null ? null : existing.startsAt()), instant(body, "endsAt", existing == null ? null : existing.endsAt()),
                body.hasKey("maxParticipants") ? (int) number(body, "maxParticipants", 0) : existing == null ? 0 : existing.maxParticipants(), config);
    }

    private DataObject eventJson(Event e) {
        DataObject config;
        try {
            config = DataObject.fromJson(e.config());
        } catch (RuntimeException ex) {
            config = DataObject.empty();
        }
        return DataObject.empty().put("id", Long.toString(e.id())).put("name", e.name()).put("description", e.description()).put("template", e.template())
                .put("status", e.status().name()).put("scope", e.scope().name()).put("scoringMode", e.scoringMode().name())
                .put("startsAt", e.startsAt() == null ? null : e.startsAt().toString()).put("endsAt", e.endsAt() == null ? null : e.endsAt().toString())
                .put("maxParticipants", e.maxParticipants()).put("config", config).put("createdAt", e.createdAt().toString());
    }

    private static DataObject objectiveJson(ObjectiveSpec o) {
        DataArray prerequisites = DataArray.empty();
        o.prerequisites().forEach(prerequisites::add);
        return DataObject.empty().put("key", o.key()).put("label", o.label()).put("description", o.description()).put("points", o.points())
                .put("evidence", o.evidence().name()).put("target", o.target()).put("repeatLimit", o.repeatLimit()).put("prerequisites", prerequisites);
    }

    private DataObject claimJson(Guild guild, Claim c) {
        return DataObject.empty().put("id", Long.toString(c.id())).put("objective", c.objectiveKey()).put("userId", Long.toString(c.userId())).put("userName", name(guild, c.userId()))
                .put("amount", c.amount()).put("note", c.note()).put("proofUrl", c.proofUrl()).put("status", c.status().name())
                .put("decidedByName", c.decidedBy() == null ? null : name(guild, c.decidedBy())).put("decisionNote", c.decisionNote())
                .put("createdAt", c.createdAt().toString()).put("decidedAt", c.decidedAt() == null ? null : c.decidedAt().toString());
    }

    // ---------- small helpers ----------

    private static long idOf(String raw, String what) {
        if (raw == null || !raw.matches("\\d{1,18}")) throw new ApiError(400, "That isn't a valid " + what + ".");
        return Long.parseLong(raw);
    }

    private static Long optionalId(DataObject body, String key) {
        if (!body.hasKey(key) || body.isNull(key)) return null;
        String raw = body.getString(key, "").strip();
        if (raw.isEmpty()) return null;
        if (!raw.matches("\\d{1,20}")) throw new ApiError(400, "That isn't a valid id.");
        return Long.parseLong(raw);
    }

    private static String text(DataObject body, String key) {
        return body.hasKey(key) && !body.isNull(key) ? body.getString(key, "").strip() : "";
    }

    private static long number(DataObject body, String key, long fallback) {
        if (!body.hasKey(key) || body.isNull(key)) return fallback;
        try {
            return body.getLong(key);
        } catch (RuntimeException e) {
            throw new ApiError(400, "\"" + key + "\" must be a whole number.");
        }
    }

    private static Instant instant(DataObject body, String key, Instant fallback) {
        if (!body.hasKey(key)) return fallback;
        if (body.isNull(key) || body.getString(key, "").isBlank()) return null;
        try {
            return Instant.parse(body.getString(key).strip());
        } catch (DateTimeParseException e) {
            throw new ApiError(400, "\"" + key + "\" must be a date and time like 2026-10-20T19:00:00Z.");
        }
    }

    private static String name(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        return member == null ? null : member.getEffectiveName();
    }
}
