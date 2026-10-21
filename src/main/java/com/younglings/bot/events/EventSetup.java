package com.younglings.bot.events;

import com.younglings.bot.events.EventBlueprintRepository.Blueprint;
import com.younglings.bot.events.EventDraftRepository.Draft;
import com.younglings.bot.events.EventOptions.Missing;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventService.EventInput;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Setting an event up, in the ways the dashboard offers: the standard setup (one click, every option at its standard value), the quick setup (a few
 * answers on top of the standard ones), the guided setup (every option, saved as a draft as it goes so it can be finished later) and saved templates (a
 * setup kept under a name). They all end in {@link #create}, which checks the answers against {@link EventOptions} and then the engine's own rules, so a
 * setup can't produce an event any other way couldn't.
 */
@BService
public class EventSetup {
    public static final int MAX_DRAFTS = 20;
    public static final int MAX_TEMPLATES = 50;
    public static final int MAX_TEMPLATE_NAME = 60;
    public static final int MIN_TEMPLATE_NAME = 3;
    public static final int MAX_TEMPLATE_DESCRIPTION = 300;
    /** A draft's answers can't be bigger than this, so a draft can't be used to store a pile of data. */
    public static final int MAX_ANSWERS_CHARS = 100_000;

    private final EventService service;
    private final EventTemplates templates;
    private final EventDraftRepository drafts;
    private final EventBlueprintRepository blueprints;
    private final EventDefaultsRepository defaults;

    public EventSetup(EventService service, EventTemplates templates, EventDraftRepository drafts, EventBlueprintRepository blueprints, EventDefaultsRepository defaults) {
        this.defaults = defaults;
        this.service = service;
        this.templates = templates;
        this.drafts = drafts;
        this.blueprints = blueprints;
    }

    private EventTemplate template(String key) {
        return templates.byKey(key == null ? "" : key).orElseThrow(() -> new EventProblem(400, "That kind of event doesn't exist."));
    }

    public List<EventTemplate> kinds() {
        return templates.all();
    }

    /** The options, steps and standard answers for a kind of event, for the website to draw the setup from. */
    public DataObject schema(long guildId, String templateKey, Instant now) {
        return EventOptions.schema(template(templateKey), now, defaults.get(guildId));
    }

    // ---------- the server's own defaults ----------

    public EventDefaults defaults(long guildId) {
        return defaults.get(guildId);
    }

    public EventDefaults saveDefaults(long guildId, long actorId, DataObject body) {
        EventDefaults parsed;
        try {
            parsed = EventDefaults.parse(body);
        } catch (IllegalArgumentException e) {
            throw new EventProblem(400, e.getMessage());
        }
        defaults.save(guildId, parsed, actorId);
        return parsed;
    }

    // ---------- creating ----------

    /**
     * Creates an event from the answers: they are cleaned up, checked for what is still missing and for the prizes, then handed to the engine. The event starts
     * as a draft (not open for signups). The prizes are saved with it; if they can't be saved the event isn't left behind. Returns the new event's id.
     */
    public long create(long guildId, long actorId, DataObject answers) {
        DataObject a = EventOptions.normalize(answers);
        EventTemplate template = template(a.hasKey("template") && !a.isNull("template") ? a.getString("template") : "custom");
        a.put("template", template.key());
        List<Missing> missing = EventOptions.missing(EventOptions.of(template), a);
        if (!missing.isEmpty()) throw new EventProblem(400, "The event can't be created yet.", missing.stream().map(Missing::message).distinct().toList());

        List<PrizePlan.Prize> prizes;
        EventInput input;
        try {
            prizes = EventOptions.prizesOf(a);
            input = EventOptions.toInput(a);
        } catch (IllegalArgumentException e) {
            throw new EventProblem(400, e.getMessage());
        }
        List<String> prizeProblems = new ArrayList<>();
        for (PrizePlan.Prize p : prizes) prizeProblems.addAll(PrizePlan.problems(p));
        prizeProblems.addAll(PrizePlan.setProblems(prizes));
        if (prizes.stream().anyMatch(p -> p.percentOfPot() > 0) && !EventSettings.parse(input.config()).buyIn().enabled()) {
            prizeProblems.add("A prize can only be a share of the pot when the event has a buy-in.");
        }
        if (!prizeProblems.isEmpty()) throw new EventProblem(400, "The prizes can't be saved yet.", prizeProblems.stream().distinct().toList());

        long id = service.createEvent(guildId, actorId, input);
        if (!prizes.isEmpty()) {
            try {
                service.setPrizes(guildId, id, prizes);
            } catch (RuntimeException e) {
                service.deleteEvent(guildId, id); // never leave an event without the prizes it was made with
                throw e;
            }
        }
        return id;
    }

    /**
     * The standard setup, or the quick one: the standard answers for {@code templateKey} made at {@code now}, with {@code overrides} (a flat object of option
     * paths to answers, like {@code {"name": "Friday bingo", "config.boss": "Araxxor"}}) laid over them.
     */
    public long createStandard(long guildId, long actorId, String templateKey, DataObject overrides, Instant now) {
        EventTemplate template = template(templateKey);
        DataObject answers = EventOptions.standard(template, now, defaults.get(guildId));
        if (overrides != null) {
            for (String path : overrides.keys()) {
                if (path.equals("template")) continue;
                if (!path.matches("[A-Za-z]+(\\.[A-Za-z]+)*")) throw new EventProblem(400, "That isn't an option of the event.");
                EventOptions.set(answers, path, overrides.isNull(path) ? null : overrides.get(path));
            }
        }
        return create(guildId, actorId, answers);
    }

    // ---------- drafts ----------

    public List<Draft> drafts(long guildId) {
        return drafts.list(guildId);
    }

    public Draft draft(long guildId, long id) {
        return drafts.get(guildId, id).orElseThrow(() -> new EventProblem(404, "That draft doesn't exist any more. It may have been finished or deleted."));
    }

    /** Starts a draft with the standard answers for a kind of event. */
    public Draft startDraft(long guildId, long actorId, String templateKey, String stream, Instant now) {
        EventTemplate template = template(templateKey);
        if (drafts.count(guildId) >= MAX_DRAFTS) throw new EventProblem(409, "A server can keep up to " + MAX_DRAFTS + " unfinished setups. Finish or delete one first.");
        DataObject answers = EventOptions.standard(template, now, defaults.get(guildId));
        String name = answers.getString("name", template.label());
        long id = drafts.create(guildId, actorId, stream == null || stream.isBlank() ? "GUIDED" : stream.strip().toUpperCase(java.util.Locale.ROOT), EventOptions.Step.BASICS.name(), template.key(), name, answers.toString());
        return draft(guildId, id);
    }

    /** Saves the answers so far and where the setup was up to. The kind of event can't change; start another draft for that. */
    public Draft saveDraft(long guildId, long id, DataObject answers, String step) {
        Draft existing = draft(guildId, id);
        String json = answers.toString();
        if (json.length() > MAX_ANSWERS_CHARS) throw new EventProblem(400, "That setup is too big to keep as a draft.");
        DataObject kept = DataObject.fromJson(json);
        kept.put("template", existing.template());
        String newStep = step == null || step.isBlank() ? existing.step() : step.strip().toUpperCase(java.util.Locale.ROOT);
        if (!newStep.equals("REVIEW") && java.util.Arrays.stream(EventOptions.Step.values()).noneMatch(s -> s.name().equals(newStep))) throw new EventProblem(400, "That isn't a step of the setup.");
        String name = kept.hasKey("name") && !kept.isNull("name") ? String.valueOf(kept.get("name")).strip() : "";
        String title = name.isEmpty() ? existing.title() : name.length() > EventRules.MAX_NAME ? name.substring(0, EventRules.MAX_NAME) : name;
        if (!drafts.save(guildId, id, newStep, title, kept.toString())) throw new EventProblem(404, "That draft doesn't exist any more. It may have been finished or deleted.");
        return draft(guildId, id);
    }

    public void deleteDraft(long guildId, long id) {
        if (!drafts.delete(guildId, id)) throw new EventProblem(404, "That draft doesn't exist any more.");
    }

    /** Finishes a draft: creates the event from its answers and removes the draft. If the event can't be created the draft stays, so nothing is lost. */
    public long createFromDraft(long guildId, long actorId, long draftId) {
        Draft draft = draft(guildId, draftId);
        long eventId = create(guildId, actorId, DataObject.fromJson(draft.answers()));
        drafts.delete(guildId, draftId);
        return eventId;
    }

    // ---------- saved templates ----------

    public List<Blueprint> savedTemplates(long guildId) {
        return blueprints.list(guildId);
    }

    public Blueprint savedTemplate(long guildId, long id) {
        return blueprints.get(guildId, id).orElseThrow(() -> new EventProblem(404, "That saved template doesn't exist any more."));
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.length() < MIN_TEMPLATE_NAME || n.length() > MAX_TEMPLATE_NAME) throw new EventProblem(400, "Give the template a name of " + MIN_TEMPLATE_NAME + " to " + MAX_TEMPLATE_NAME + " characters.");
        return n;
    }

    private static String cleanDescription(String description) {
        String d = description == null ? "" : description.strip();
        if (d.length() > MAX_TEMPLATE_DESCRIPTION) throw new EventProblem(400, "Keep the template's description to " + MAX_TEMPLATE_DESCRIPTION + " characters or fewer.");
        return d;
    }

    /** Settings that belong to one event (what it generated, who runs it, when signups close) and so are not kept in a saved template. */
    private static final List<String> PER_EVENT = List.of("config.board.keys", "config.board.seed", "config.bonuses", "config.organisers", "config.signupClosesAt");

    /**
     * Keeps an existing event's setup as a saved template: its settings, its prizes, how long it runs, and how long before it starts signups closed. What the
     * event generated (a bingo board's tiles, its bonuses) is left out, so each event made from the template gets its own.
     */
    public Blueprint saveTemplateFromEvent(long guildId, long actorId, long eventId, String name, String description) {
        String cleanName = cleanName(name);
        String cleanDescription = cleanDescription(description);
        Event event = service.event(guildId, eventId);
        if (blueprints.count(guildId) >= MAX_TEMPLATES) throw new EventProblem(409, "A server can keep up to " + MAX_TEMPLATES + " saved templates. Delete one first.");
        DataObject config = DataObject.fromJson(event.config());
        DataObject body = DataObject.empty().put("template", event.template()).put("description", event.description()).put("scope", event.scope().name()).put("scoringMode", event.scoringMode().name())
                .put("maxParticipants", event.maxParticipants()).put("config", config);
        DataObject timing = DataObject.empty().put("startsInHours", EventOptions.STANDARD_STARTS_IN_HOURS).put("days", EventOptions.STANDARD_DAYS);
        if (event.startsAt() != null && event.endsAt() != null && event.endsAt().isAfter(event.startsAt())) {
            long hours = Duration.between(event.startsAt(), event.endsAt()).toHours();
            timing.put("days", Math.max(1, Math.round(hours / 24.0)));
        }
        Instant closes = EventSettings.parse(config).signupClosesAt();
        if (closes != null && event.startsAt() != null && !closes.isAfter(event.startsAt())) timing.put("signupClosesBeforeStartHours", Duration.between(closes, event.startsAt()).toHours());
        for (String path : PER_EVENT) EventOptions.set(body, path, null);
        if (body.getObject("config").hasKey("board") && body.getObject("config").getObject("board").hasKey("layout") && !body.getObject("config").getObject("board").isNull("layout")) {
            EventOptions.set(body, "config.board.size", null); // a shape has no square size
        }
        DataArray prizes = DataArray.empty();
        service.prizes(eventId).forEach(p -> prizes.add(DataObject.empty().put("place", p.place()).put("placeTo", p.placeTo()).put("kind", p.kind().name()).put("description", p.description())
                .put("amount", p.amount()).put("percentOfPot", p.percentOfPot()).put("split", p.split().name())));
        OptionalLong id = blueprints.create(guildId, actorId, cleanName, cleanDescription, event.template(), body.toString(), timing.toString(), prizes.toString());
        if (id.isEmpty()) throw new EventProblem(409, "There is already a saved template called \"" + cleanName + "\". Choose another name.");
        return savedTemplate(guildId, id.getAsLong());
    }

    public Blueprint renameTemplate(long guildId, long id, String name, String description) {
        savedTemplate(guildId, id);
        int result = blueprints.rename(guildId, id, cleanName(name), cleanDescription(description));
        if (result < 0) throw new EventProblem(409, "There is already a saved template with that name. Choose another.");
        if (result == 0) throw new EventProblem(404, "That saved template doesn't exist any more.");
        return savedTemplate(guildId, id);
    }

    public void deleteTemplate(long guildId, long id) {
        if (!blueprints.delete(guildId, id)) throw new EventProblem(404, "That saved template doesn't exist any more.");
    }

    /**
     * Makes a new event from a saved template: its settings and prizes, starting {@code startsAt} (or as soon as the template says, by default a day from
     * {@code now}) and running as long as the template did. {@code name} is the new event's name; blank gives the template's name and the start day.
     */
    public long createFromTemplate(long guildId, long actorId, long templateId, String name, Instant startsAt, Instant now) {
        Blueprint blueprint = savedTemplate(guildId, templateId);
        DataObject body = DataObject.fromJson(blueprint.body());
        DataObject timing = DataObject.fromJson(blueprint.timing());
        Instant starts = startsAt != null ? startsAt : now.plus(Duration.ofHours(timing.getLong("startsInHours", EventOptions.STANDARD_STARTS_IN_HOURS)));
        Instant ends = starts.plus(Duration.ofDays(Math.max(1, timing.getLong("days", EventOptions.STANDARD_DAYS))));
        body.put("startsAt", starts.toString()).put("endsAt", ends.toString());
        if (timing.hasKey("signupClosesBeforeStartHours") && !timing.isNull("signupClosesBeforeStartHours")) {
            EventOptions.set(body, "config.signupClosesAt", starts.minus(Duration.ofHours(timing.getLong("signupClosesBeforeStartHours"))).toString());
        }
        body.put("name", name == null || name.isBlank() ? EventOptions.standardName(blueprint.name(), starts) : name.strip());
        body.put("prizes", DataArray.fromJson(blueprint.prizes()));
        return create(guildId, actorId, body);
    }
}
