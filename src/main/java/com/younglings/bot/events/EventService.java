package com.younglings.bot.events;

import com.younglings.bot.events.EventRepository.Claim;
import com.younglings.bot.events.EventRepository.ClaimStatus;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRepository.Participant;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Source;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.EventScoring.Entry;
import com.younglings.bot.events.EventScoring.Standing;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * The event engine's rules applied to real events: who may do what in which stage, one answer for every way of getting here (the dashboard
 * today, Discord and the plugin later). Refusals are {@link EventProblem}s with a reason an admin can read. Standings are never stored: they
 * are worked out from the progress log by {@link EventScoring}.
 */
@BService
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    /** A server can have this many events that aren't finished, so one server can't pile up unlimited work. */
    public static final int MAX_OPEN_EVENTS = 20;
    public static final int MAX_PENDING_CLAIMS_PER_PLAYER = 5;

    /** The settings of an event as admins send them. */
    public record EventInput(String name, String description, String template, Scope scope, ScoringMode scoringMode, Instant startsAt, Instant endsAt,
                             int maxParticipants, DataObject config) {}

    public record Recorded(long progressId, long total, int completions) {}

    private final EventRepository repository;
    private final EventTemplates templates;

    public EventService(EventRepository repository, EventTemplates templates) {
        this.repository = repository;
        this.templates = templates;
    }

    // ---------- events ----------

    public long createEvent(long guildId, long actorId, EventInput in) {
        EventTemplate template = templates.byKey(in.template()).orElseThrow(() -> new EventProblem(400, "That kind of event doesn't exist."));
        DataObject config = in.config() == null ? DataObject.empty() : in.config();
        List<String> problems = new ArrayList<>(EventRules.eventProblems(in.name(), in.description(), in.maxParticipants(), in.startsAt(), in.endsAt()));
        problems.addAll(template.configProblems(config));
        if (!problems.isEmpty()) throw new EventProblem(400, "The event can't be created yet.", problems);
        if (repository.countOpen(guildId) >= MAX_OPEN_EVENTS) throw new EventProblem(409, "A server can have " + MAX_OPEN_EVENTS + " unfinished events. Finish or cancel one first.");
        long id = repository.createEvent(guildId, template.key(), in.name().strip(), clean(in.description()), in.scope(), in.scoringMode(), in.startsAt(), in.endsAt(),
                in.maxParticipants(), config.toString(), actorId);
        List<ObjectiveSpec> starters = template.starterObjectives(config);
        if (!starters.isEmpty()) {
            List<String> starterProblems = checkObjectives(starters);
            if (!starterProblems.isEmpty()) {
                log.error("Template {} produced invalid starter objectives: {}", template.key(), starterProblems);
                throw new EventProblem(500, "That template couldn't set itself up.");
            }
            repository.replaceObjectives(id, starters);
        }
        return id;
    }

    public Event event(long guildId, long id) {
        return repository.event(guildId, id).orElseThrow(() -> new EventProblem(404, "That event doesn't exist in this server."));
    }

    public void updateEvent(long guildId, long id, EventInput in) {
        Event existing = event(guildId, id);
        if (!EventRules.editable(existing.status())) throw new EventProblem(409, "An event can only be changed while it is a draft or taking signups.");
        EventTemplate template = templates.byKey(existing.template()).orElseThrow(() -> new EventProblem(409, "This event's template no longer exists."));
        DataObject config = in.config() == null ? DataObject.fromJson(existing.config()) : in.config();
        List<String> problems = new ArrayList<>(EventRules.eventProblems(in.name(), in.description(), in.maxParticipants(), in.startsAt(), in.endsAt()));
        problems.addAll(template.configProblems(config));
        if (!problems.isEmpty()) throw new EventProblem(400, "The event can't be saved yet.", problems);
        if (!repository.updateEvent(guildId, id, in.name().strip(), clean(in.description()), in.scope(), in.scoringMode(), in.startsAt(), in.endsAt(), in.maxParticipants(), config.toString())) {
            throw new EventProblem(409, "The event moved on while you were editing it.");
        }
    }

    public void moveStage(long guildId, long id, Status to) {
        Event event = event(guildId, id);
        if (!EventRules.canMove(event.status(), to)) {
            throw new EventProblem(409, "An event can't go from " + label(event.status()) + " to " + label(to) + ".");
        }
        if (to == Status.RUNNING && repository.objectives(id).isEmpty()) throw new EventProblem(409, "Add at least one objective before the event starts.");
        if (!repository.moveStage(guildId, id, event.status(), to)) throw new EventProblem(409, "Someone else already moved this event.");
    }

    public void deleteDraft(long guildId, long id) {
        event(guildId, id);
        if (!repository.deleteEvent(guildId, id)) throw new EventProblem(409, "Only a draft can be deleted. Cancel an event that has started.");
    }

    // ---------- objectives, teams, players ----------

    public List<ObjectiveSpec> objectives(long eventId) {
        return repository.objectives(eventId);
    }

    public void setObjectives(long guildId, long id, List<ObjectiveSpec> objectives) {
        Event event = event(guildId, id);
        if (!EventRules.editable(event.status())) throw new EventProblem(409, "Objectives can only be changed while the event is a draft or taking signups.");
        List<String> problems = checkObjectives(objectives);
        if (!problems.isEmpty()) throw new EventProblem(400, "The objectives can't be saved yet.", problems);
        repository.replaceObjectives(id, objectives);
    }

    /** Problems with each objective and with the set as a whole. */
    public static List<String> checkObjectives(List<ObjectiveSpec> objectives) {
        List<String> problems = new ArrayList<>();
        for (ObjectiveSpec o : objectives) problems.addAll(EventRules.objectiveProblems(o));
        problems.addAll(EventRules.objectiveSetProblems(objectives));
        return problems.stream().distinct().toList();
    }

    public long addTeam(long guildId, long id, String name, Long captainId) {
        Event event = event(guildId, id);
        if (!EventRules.editable(event.status())) throw new EventProblem(409, "Teams can only be changed while the event is a draft or taking signups.");
        String problem = EventRules.teamNameProblem(name);
        if (problem != null) throw new EventProblem(400, problem);
        OptionalLong team = repository.addTeam(id, name.strip(), captainId, EventRules.MAX_TEAMS);
        if (team.isEmpty()) throw new EventProblem(409, "There is already a team with that name, or the event has the most teams allowed (" + EventRules.MAX_TEAMS + ").");
        return team.getAsLong();
    }

    public void removeTeam(long guildId, long id, long teamId) {
        Event event = event(guildId, id);
        if (!EventRules.editable(event.status())) throw new EventProblem(409, "Teams can only be changed while the event is a draft or taking signups.");
        if (!repository.removeTeam(id, teamId)) throw new EventProblem(404, "That team doesn't exist.");
    }

    /** Adds a player. {@code userGuildId} is the server they play from (the host's own server for now). */
    public void join(long guildId, long id, long userGuildId, long userId, Long teamId) {
        Event event = event(guildId, id);
        if (!EventRules.acceptsJoins(event.status())) throw new EventProblem(409, "This event isn't taking signups.");
        if (teamId != null && repository.teams(id).stream().noneMatch(t -> t.id() == teamId)) throw new EventProblem(404, "That team doesn't exist.");
        if (event.scoringMode() == ScoringMode.TEAM && teamId == null && event.status() == Status.RUNNING) {
            throw new EventProblem(400, "Pick a team: this event is scored by team and it has already started.");
        }
        if (!repository.join(id, userId, userGuildId, teamId, event.maxParticipants())) {
            throw new EventProblem(409, repository.participant(id, userId).isPresent() ? "That player is already in the event." : "The event is full.");
        }
    }

    public void leave(long guildId, long id, long userId) {
        event(guildId, id);
        if (!repository.leave(id, userId)) throw new EventProblem(404, "That player isn't in the event.");
    }

    public void setTeam(long guildId, long id, long userId, Long teamId) {
        Event event = event(guildId, id);
        if (event.status() == Status.DONE || event.status() == Status.CANCELLED) throw new EventProblem(409, "This event is over.");
        if (teamId != null && repository.teams(id).stream().noneMatch(t -> t.id() == teamId)) throw new EventProblem(404, "That team doesn't exist.");
        if (!repository.setTeam(id, userId, teamId)) throw new EventProblem(404, "That player isn't in the event.");
    }

    // ---------- progress ----------

    /**
     * Records progress for a player. Checks the event is running, the player is in it, the objective accepts progress from this source, its
     * prerequisites are done, and it can still count; trims the amount so an objective never goes past its last completion. An organiser
     * ({@link Source#MANUAL}) may also enter a negative amount to undo earlier progress. {@code sourceKey} makes a retry harmless.
     */
    public Recorded recordProgress(long guildId, long id, String objectiveKey, long userId, long amount, Source source, String sourceKey, Long actorId, String note) {
        Event event = event(guildId, id);
        if (!EventRules.acceptsProgress(event.status())) throw new EventProblem(409, "This event isn't running, so progress doesn't count right now.");
        if (amount == 0) throw new EventProblem(400, "The amount can't be zero.");
        if (amount < 0 && source != Source.MANUAL) throw new EventProblem(400, "Only an organiser can take progress back.");
        String cleanNote = clean(note);
        if (cleanNote.length() > EventRules.MAX_NOTE) throw new EventProblem(400, "Keep the note to " + EventRules.MAX_NOTE + " characters or fewer.");

        List<ObjectiveSpec> objectives = repository.objectives(id);
        ObjectiveSpec objective = objectives.stream().filter(o -> o.key().equals(objectiveKey)).findFirst().orElseThrow(() -> new EventProblem(404, "That objective doesn't exist."));
        if (!EventRules.evidenceAllows(objective.evidence(), source)) throw new EventProblem(409, "\"" + objective.label() + "\" doesn't accept progress that way.");
        Participant player = repository.participant(id, userId).orElseThrow(() -> new EventProblem(404, "That player isn't in the event."));

        boolean byTeam = event.scoringMode() == ScoringMode.TEAM;
        if (byTeam && player.teamId() == null) throw new EventProblem(409, "Put that player in a team first: this event is scored by team.");
        long subject = byTeam ? player.teamId() : userId;

        long total = repository.total(id, objectiveKey, subject, byTeam);
        long capacity = objective.target() * objective.repeatLimit();
        long effective = amount;
        if (amount > 0) {
            Map<String, ObjectiveSpec> byKey = new HashMap<>();
            for (ObjectiveSpec o : objectives) byKey.put(o.key(), o);
            Map<String, Long> totals = new HashMap<>();
            for (String prerequisite : objective.prerequisites()) totals.put(prerequisite, repository.total(id, prerequisite, subject, byTeam));
            if (!EventScoring.available(objective, totals, byKey)) throw new EventProblem(409, "\"" + objective.label() + "\" isn't unlocked yet: finish what it needs first.");
            long remaining = capacity - total;
            if (remaining <= 0) throw new EventProblem(409, "\"" + objective.label() + "\" is already complete" + (byTeam ? " for that team." : " for that player."));
            effective = Math.min(amount, remaining);
        } else {
            effective = -Math.min(-amount, total); // never reverse more than there is
            if (effective == 0) throw new EventProblem(409, "There is no progress to take back.");
        }

        OptionalLong row = repository.addProgress(id, objectiveKey, userId, player.teamId(), effective, source, sourceKey, actorId, cleanNote);
        if (row.isEmpty()) throw new EventProblem(409, "That has already been counted.");
        long after = Math.max(0, total + effective);
        return new Recorded(row.getAsLong(), after, EventScoring.completions(objective, after));
    }

    // ---------- claims ----------

    /** A player's proof for an objective, waiting for an organiser. */
    public long submitClaim(long guildId, long id, String objectiveKey, long userId, long amount, String note, String proofUrl) {
        Event event = event(guildId, id);
        if (!EventRules.acceptsProgress(event.status())) throw new EventProblem(409, "This event isn't running, so claims aren't open.");
        ObjectiveSpec objective = repository.objectives(id).stream().filter(o -> o.key().equals(objectiveKey)).findFirst()
                .orElseThrow(() -> new EventProblem(404, "That objective doesn't exist."));
        if (!EventRules.evidenceAllows(objective.evidence(), Source.CLAIM)) throw new EventProblem(409, "\"" + objective.label() + "\" can't be claimed with proof.");
        if (repository.participant(id, userId).isEmpty()) throw new EventProblem(404, "That player isn't in the event.");
        if (amount < 1 || amount > objective.target()) throw new EventProblem(400, "Claim between 1 and " + objective.target() + ".");
        String cleanNote = clean(note);
        if (cleanNote.length() > EventRules.MAX_NOTE) throw new EventProblem(400, "Keep the note to " + EventRules.MAX_NOTE + " characters or fewer.");
        if (cleanNote.length() < 3 && (proofUrl == null || proofUrl.isBlank())) throw new EventProblem(400, "Say what you did, or attach proof.");
        if (repository.pendingClaimsFor(id, userId) >= MAX_PENDING_CLAIMS_PER_PLAYER) {
            throw new EventProblem(409, "You already have " + MAX_PENDING_CLAIMS_PER_PLAYER + " claims waiting. Wait for the organisers to look at them first.");
        }
        return repository.addClaim(id, objectiveKey, userId, amount, cleanNote, proofUrl == null || proofUrl.isBlank() ? null : proofUrl.strip());
    }

    /**
     * Approves or denies a pending claim. Approving counts its amount as progress under the key {@code claim:<id>}, so even a repeat after a
     * crash counts it once. The claim is checked against the event first, so an approval that can't be counted is refused before anything changes.
     */
    public Claim decideClaim(long guildId, long id, long claimId, boolean approve, String note, long actorId) {
        Event event = event(guildId, id);
        Claim claim = repository.claim(id, claimId).orElseThrow(() -> new EventProblem(404, "That claim doesn't exist."));
        if (claim.status() != ClaimStatus.PENDING) throw new EventProblem(409, "Someone has already decided that claim.");
        String cleanNote = clean(note);
        if (cleanNote.length() > EventRules.MAX_NOTE) throw new EventProblem(400, "Keep the note to " + EventRules.MAX_NOTE + " characters or fewer.");
        if (!approve && cleanNote.isEmpty()) throw new EventProblem(400, "Say why it was denied, so the player can fix it.");
        if (approve && !EventRules.acceptsProgress(event.status())) throw new EventProblem(409, "The event isn't running, so an approval couldn't be counted. Reopen it first.");

        if (!repository.decideClaim(id, claimId, approve ? ClaimStatus.APPROVED : ClaimStatus.DENIED, actorId, cleanNote.isEmpty() ? null : cleanNote)) {
            throw new EventProblem(409, "Someone has already decided that claim.");
        }
        if (approve) {
            try {
                Recorded recorded = recordProgress(guildId, id, claim.objectiveKey(), claim.userId(), claim.amount(), Source.CLAIM, "claim:" + claimId, actorId, "Approved claim #" + claimId);
                repository.linkClaimProgress(claimId, recorded.progressId());
            } catch (EventProblem e) {
                // the objective filled up or the player left between the check and now: the claim stays approved, with nothing to count
                log.warn("Approved claim {} of event {} couldn't be counted: {}", claimId, id, e.getMessage());
            }
        }
        return repository.claim(id, claimId).orElse(claim);
    }

    // ---------- standings ----------

    /** Standings from the progress log. Subjects are "u:&lt;userId&gt;" for players or "t:&lt;teamId&gt;" when the event scores by team. */
    public List<Standing> standings(long guildId, long id) {
        Event event = event(guildId, id);
        boolean byTeam = event.scoringMode() == ScoringMode.TEAM;
        List<Entry> log = new ArrayList<>();
        for (EventRepository.Progress p : repository.progress(id)) {
            if (byTeam && p.teamId() == null) continue;
            log.add(new Entry(byTeam ? "t:" + p.teamId() : "u:" + p.userId(), p.objectiveKey(), p.amount(), p.createdAt()));
        }
        return EventScoring.standings(repository.objectives(id), log);
    }

    private static String clean(String text) {
        return text == null ? "" : text.strip();
    }

    private static String label(Status status) {
        return switch (status) {
            case DRAFT -> "a draft";
            case SIGNUPS -> "taking signups";
            case RUNNING -> "running";
            case SCORING -> "scoring";
            case DONE -> "done";
            case CANCELLED -> "cancelled";
        };
    }
}
