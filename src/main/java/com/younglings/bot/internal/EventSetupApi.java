package com.younglings.bot.internal;

import com.younglings.bot.events.EventBlueprintRepository.Blueprint;
import com.younglings.bot.events.EventDraftRepository.Draft;
import com.younglings.bot.events.EventOptions;
import com.younglings.bot.events.EventSetup;
import com.younglings.bot.events.EventTemplate;
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
import java.util.List;
import java.util.function.Supplier;

/**
 * The dashboard's routes for setting an event up, reached through {@link EventsAdminApi} (so the asker has already been checked and the change audited).
 * <ul>
 *   <li>{@code GET events/setup/schema/{template}}: the steps, options and standard answers of a kind of event.</li>
 *   <li>{@code POST events/setup/standard}: the standard or quick setup, {@code {template, overrides}} (a flat object of option paths to answers).</li>
 *   <li>{@code POST events/setup/create}: a full set of {@code {answers}}.</li>
 *   <li>{@code GET/POST events/drafts}, {@code GET/PUT/DELETE events/drafts/{id}}, {@code POST events/drafts/{id}/create}: unfinished setups.</li>
 *   <li>{@code GET/POST events/saved-templates}, {@code PUT/DELETE events/saved-templates/{id}}, {@code POST events/saved-templates/{id}/create}: setups kept by name.</li>
 * </ul>
 * Every rule is {@link EventSetup}'s and the engine's.
 */
@BService
public class EventSetupApi {
    private static final Logger log = LoggerFactory.getLogger(EventSetupApi.class);

    private final EventSetup setup;

    public EventSetupApi(EventSetup setup) {
        this.setup = setup;
    }

    /** Whether {@code first} (the part after {@code events/}) is one of this class's routes. */
    public static boolean handles(String first) {
        return first.equals("setup") || first.equals("drafts") || first.equals("saved-templates");
    }

    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        return switch (parts[1]) {
            case "setup" -> setupRoute(guild, actor, method, parts, body);
            case "drafts" -> draftRoute(guild, actor, method, parts, body);
            default -> templateRoute(guild, actor, method, parts, body);
        };
    }

    // ---------- the setup itself ----------

    private DataObject setupRoute(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length == 4 && parts[2].equals("schema")) {
            AdminRouteExtension.requireMethod(method, "GET");
            return setup.schema(parts[3], Instant.now());
        }
        if (parts.length == 3 && parts[2].equals("standard")) {
            AdminRouteExtension.requireMethod(method, "POST");
            DataObject b = body.get();
            DataObject overrides = b.hasKey("overrides") && !b.isNull("overrides") ? b.getObject("overrides") : DataObject.empty();
            long id = setup.createStandard(guild.getIdLong(), actor.getIdLong(), text(b, "template"), overrides, Instant.now());
            log.info("Dashboard: {} made event {} from the standard setup of {}", actor.getId(), id, text(b, "template"));
            return created(id);
        }
        if (parts.length == 3 && parts[2].equals("create")) {
            AdminRouteExtension.requireMethod(method, "POST");
            DataObject b = body.get();
            if (!b.hasKey("answers") || b.isNull("answers")) throw new ApiError(400, "Send the setup's answers.");
            long id = setup.create(guild.getIdLong(), actor.getIdLong(), b.getObject("answers"));
            log.info("Dashboard: {} created event {} from a full setup", actor.getId(), id);
            return created(id);
        }
        return null;
    }

    private static DataObject created(long id) {
        return DataObject.empty().put("id", Long.toString(id));
    }

    // ---------- drafts ----------

    private DataObject draftRoute(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        long guildId = guild.getIdLong();
        if (parts.length == 2) {
            if (method.equals("GET")) return draftList(guild);
            AdminRouteExtension.requireMethod(method, "POST");
            DataObject b = body.get();
            Draft draft = setup.startDraft(guildId, actor.getIdLong(), text(b, "template"), text(b, "stream"), Instant.now());
            log.info("Dashboard: {} started setup draft {} ({})", actor.getId(), draft.id(), draft.template());
            return DataObject.empty().put("draft", draftJson(guild, draft, true));
        }
        long id = idOf(parts[2], "draft");
        if (parts.length == 3) {
            return switch (method) {
                case "GET" -> DataObject.empty().put("draft", draftJson(guild, setup.draft(guildId, id), true));
                case "PUT" -> {
                    DataObject b = body.get();
                    if (!b.hasKey("answers") || b.isNull("answers")) throw new ApiError(400, "Send the setup's answers.");
                    yield DataObject.empty().put("draft", draftJson(guild, setup.saveDraft(guildId, id, b.getObject("answers"), text(b, "step")), true));
                }
                case "DELETE" -> {
                    setup.deleteDraft(guildId, id);
                    log.info("Dashboard: {} deleted setup draft {}", actor.getId(), id);
                    yield draftList(guild);
                }
                default -> throw new ApiError(405, "Method not allowed");
            };
        }
        if (parts.length == 4 && parts[3].equals("create")) {
            AdminRouteExtension.requireMethod(method, "POST");
            DataObject b = body.get();
            // the answers on screen are saved first, so what is created is what the admin sees
            if (b.hasKey("answers") && !b.isNull("answers")) setup.saveDraft(guildId, id, b.getObject("answers"), text(b, "step"));
            long eventId = setup.createFromDraft(guildId, actor.getIdLong(), id);
            log.info("Dashboard: {} finished setup draft {} as event {}", actor.getId(), id, eventId);
            return created(eventId);
        }
        return null;
    }

    private DataObject draftList(Guild guild) {
        DataArray array = DataArray.empty();
        setup.drafts(guild.getIdLong()).forEach(d -> array.add(draftJson(guild, d, false)));
        return DataObject.empty().put("drafts", array).put("max", EventSetup.MAX_DRAFTS);
    }

    private DataObject draftJson(Guild guild, Draft d, boolean full) {
        EventTemplate template = setup.kinds().stream().filter(t -> t.key().equals(d.template())).findFirst().orElse(null);
        DataObject answers = DataObject.fromJson(d.answers());
        List<EventOptions.Missing> missing = template == null ? List.of() : EventOptions.missing(EventOptions.of(template), EventOptions.normalize(answers));
        DataObject json = DataObject.empty().put("id", Long.toString(d.id())).put("template", d.template()).put("templateLabel", template == null ? d.template() : template.label())
                .put("stream", d.stream()).put("step", d.step()).put("title", d.title()).put("createdBy", Long.toString(d.createdBy())).put("createdByName", name(guild, d.createdBy()))
                .put("createdAt", d.createdAt().toString()).put("updatedAt", d.updatedAt().toString()).put("missingCount", missing.size());
        if (full) {
            DataArray list = DataArray.empty();
            missing.forEach(m -> list.add(DataObject.empty().put("path", m.path()).put("label", m.label()).put("step", m.step().name()).put("message", m.message())));
            json.put("answers", answers).put("missing", list);
        }
        return json;
    }

    // ---------- saved templates ----------

    private DataObject templateRoute(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        long guildId = guild.getIdLong();
        if (parts.length == 2) {
            if (method.equals("GET")) return templateList(guild);
            AdminRouteExtension.requireMethod(method, "POST");
            DataObject b = body.get();
            long eventId = idOf(text(b, "eventId"), "event");
            Blueprint saved = setup.saveTemplateFromEvent(guildId, actor.getIdLong(), eventId, text(b, "name"), text(b, "description"));
            log.info("Dashboard: {} saved event {} as the template \"{}\"", actor.getId(), eventId, saved.name());
            return templateList(guild).put("saved", templateJson(guild, saved));
        }
        long id = idOf(parts[2], "template");
        if (parts.length == 3) {
            if (method.equals("DELETE")) {
                setup.deleteTemplate(guildId, id);
                log.info("Dashboard: {} deleted the saved template {}", actor.getId(), id);
                return templateList(guild);
            }
            AdminRouteExtension.requireMethod(method, "PUT");
            DataObject b = body.get();
            setup.renameTemplate(guildId, id, text(b, "name"), text(b, "description"));
            return templateList(guild);
        }
        if (parts.length == 4 && parts[3].equals("create")) {
            AdminRouteExtension.requireMethod(method, "POST");
            DataObject b = body.get();
            Instant startsAt = null;
            if (!text(b, "startsAt").isEmpty()) {
                try {
                    startsAt = Instant.parse(text(b, "startsAt"));
                } catch (DateTimeParseException e) {
                    throw new ApiError(400, "The start has to be a date and time.");
                }
            }
            long eventId = setup.createFromTemplate(guildId, actor.getIdLong(), id, text(b, "name"), startsAt, Instant.now());
            log.info("Dashboard: {} made event {} from the saved template {}", actor.getId(), eventId, id);
            return created(eventId);
        }
        return null;
    }

    private DataObject templateList(Guild guild) {
        DataArray array = DataArray.empty();
        setup.savedTemplates(guild.getIdLong()).forEach(t -> array.add(templateJson(guild, t)));
        return DataObject.empty().put("templates", array).put("max", EventSetup.MAX_TEMPLATES);
    }

    private DataObject templateJson(Guild guild, Blueprint t) {
        EventTemplate template = setup.kinds().stream().filter(k -> k.key().equals(t.template())).findFirst().orElse(null);
        DataObject body = DataObject.fromJson(t.body());
        DataObject timing = DataObject.fromJson(t.timing());
        return DataObject.empty().put("id", Long.toString(t.id())).put("name", t.name()).put("description", t.description()).put("template", t.template())
                .put("templateLabel", template == null ? t.template() : template.label()).put("available", template != null)
                .put("scoringMode", body.getString("scoringMode", "INDIVIDUAL")).put("days", timing.getLong("days", EventOptions.STANDARD_DAYS))
                .put("prizes", DataArray.fromJson(t.prizes()).length()).put("createdBy", Long.toString(t.createdBy())).put("createdByName", name(guild, t.createdBy()))
                .put("createdAt", t.createdAt().toString());
    }

    // ---------- helpers ----------

    private static String text(DataObject body, String key) {
        return body.hasKey(key) && !body.isNull(key) ? String.valueOf(body.get(key)).strip() : "";
    }

    private static long idOf(String raw, String what) {
        if (raw == null || !raw.matches("\\d{1,19}")) throw new ApiError(400, "That isn't a valid " + what + ".");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiError(400, "That isn't a valid " + what + ".");
        }
    }

    private static String name(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        return member == null ? null : member.getEffectiveName();
    }
}
