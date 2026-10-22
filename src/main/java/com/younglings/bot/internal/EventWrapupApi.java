package com.younglings.bot.internal;

import com.younglings.bot.events.EventProblem;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.EventSettings;
import com.younglings.bot.events.EventSpaceOps;
import com.younglings.bot.events.EventWrapup;
import com.younglings.bot.events.EventWrapup.Step;
import com.younglings.bot.events.EventWrapupOps;
import com.younglings.bot.events.EventWrapupRepository;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Base64;
import java.util.function.Supplier;

/**
 * The dashboard's routes for wrapping an event up, under {@code events/{id}/wrapup}: {@code GET} says where it stands (the settings, which steps are done and
 * when the rest will happen by themselves, what each action would touch, the running job and the finished export), {@code PUT .../settings} saves the
 * settings, and {@code POST} starts an action: {@code lock}, {@code unlock}, {@code roles} (take the roles back), {@code archive}, {@code compress}
 * ({@code all}, {@code deleteOriginals}), {@code export} and {@code delete}. Anything that deletes needs {@code "confirm": true}. {@code GET .../export/file}
 * hands over the finished zip (base64). Every rule is {@link EventWrapup}'s; reached through {@link TicketAdminApi}, which has already checked the asker
 * may use the dashboard and audit-logs every change.
 */
@BService
public class EventWrapupApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(EventWrapupApi.class);

    private final EventService service;
    private final EventWrapupOps wrapupOps;
    private final EventSpaceOps spaceOps;
    private final EventWrapupRepository repository;

    public EventWrapupApi(EventService service, EventWrapupOps wrapupOps, EventSpaceOps spaceOps, EventWrapupRepository repository) {
        this.service = service;
        this.wrapupOps = wrapupOps;
        this.spaceOps = spaceOps;
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 3 || !parts[0].equals("events") || !parts[2].equals("wrapup")) return null;
        try {
            long id = idOf(parts[1]);
            Event event = service.event(guild.getIdLong(), id);
            if (parts.length == 3) {
                AdminRouteExtension.requireMethod(method, "GET");
                return view(event);
            }
            String action = parts[3];
            if (parts.length == 5 && action.equals("export") && parts[4].equals("file")) {
                AdminRouteExtension.requireMethod(method, "GET");
                return file(id);
            }
            if (parts.length != 4) return null;
            if (action.equals("settings")) {
                AdminRouteExtension.requireMethod(method, "PUT");
                service.setWrapupSettings(guild.getIdLong(), id, body.get());
                log.info("Dashboard: {} changed the wrap-up settings of event {}", actor.getId(), id);
                return view(service.event(guild.getIdLong(), id));
            }
            AdminRouteExtension.requireMethod(method, "POST");
            DataObject b = body.get();
            switch (action) {
                case "lock" -> wrapupOps.startLock(guild, event);
                case "unlock" -> wrapupOps.startUnlock(guild, event);
                case "roles" -> {
                    needConfirm(b, "This deletes the event's roles from Discord, so players lose the access they gave.");
                    wrapupOps.startRoles(guild, event);
                }
                case "archive" -> {
                    needConfirm(b, "This moves the event's channels into the archive, where only staff can see them.");
                    wrapupOps.startArchive(guild, event);
                }
                case "compress" -> {
                    boolean deleteOriginals = b.getBoolean("deleteOriginals", false);
                    if (deleteOriginals) needConfirm(b, "This deletes the original channels after their transcripts are written.");
                    wrapupOps.startCompress(guild, event, b.getBoolean("all", false), deleteOriginals);
                }
                case "export" -> wrapupOps.startExport(guild, event);
                case "delete" -> {
                    needConfirm(b, "This deletes the event's roles and channels from Discord for good. Export first if you want to keep the messages.");
                    wrapupOps.startDelete(guild, event);
                }
                default -> {
                    return null;
                }
            }
            log.info("Dashboard: {} started '{}' for the wrap-up of event {}", actor.getId(), action, id);
            return view(service.event(guild.getIdLong(), id));
        } catch (EventProblem e) {
            throw new ApiError(e.status(), e.getMessage(), e.problems());
        }
    }

    private static void needConfirm(DataObject body, String what) {
        if (!body.getBoolean("confirm", false)) throw new ApiError(400, Text.en("dashboard.say_that_you_are_sure", what));
    }

    private static long idOf(String raw) {
        if (raw == null || !raw.matches("\\d{1,18}")) throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_event"));
        return Long.parseLong(raw);
    }

    private DataObject file(long id) {
        EventWrapupOps.Export export = wrapupOps.export(id);
        if (export == null) throw new ApiError(404, Text.en("dashboard.there_is_no_export_waiting_make"));
        return DataObject.empty().put("name", export.name()).put("size", export.data().length).put("base64", Base64.getEncoder().encodeToString(export.data()));
    }

    DataObject view(Event event) {
        long id = event.id();
        EventSettings settings = service.settingsOf(event);
        EventWrapup.Setting setting = settings.wrapup();
        var row = repository.row(id);
        var counts = wrapupOps.counts(id);
        boolean over = event.status() == Status.DONE || event.status() == Status.CANCELLED;

        DataObject state = DataObject.empty().put("finishedAt", iso(row.state().finishedAt())).put("lockedAt", iso(row.state().lockedAt())).put("rolesRemovedAt", iso(row.state().rolesRemovedAt()))
                .put("channelsDoneAt", iso(row.state().channelsDoneAt())).put("channelsHow", row.channelsHow()).put("exportedAt", iso(row.exportedAt()));
        DataObject schedule = DataObject.empty().put("lockAt", iso(EventWrapup.dueAt(setting, row.state(), Step.LOCK))).put("rolesAt", iso(EventWrapup.dueAt(setting, row.state(), Step.ROLES)))
                .put("channelsAt", iso(EventWrapup.dueAt(setting, row.state(), Step.CHANNELS)));

        EventSpaceOps.Job job = spaceOps.job(id);
        DataObject jobJson = job == null ? null : DataObject.empty().put("kind", job.kind()).put("state", job.state()).put("done", job.done()).put("total", job.total()).put("message", job.message())
                .put("startedAt", job.startedAt().toString()).put("finishedAt", job.finishedAt() == null ? null : job.finishedAt().toString());
        EventWrapupOps.Export export = wrapupOps.export(id);
        DataObject exportJson = export == null ? null : DataObject.empty().put("name", export.name()).put("size", export.data().length).put("madeAt", export.madeAt().toString())
                .put("channels", export.channels()).put("messages", export.messages()).put("images", export.images()).put("links", export.links());

        return DataObject.empty().put("over", over).put("status", event.status().name()).put("settings", EventWrapup.toJson(setting)).put("state", state).put("schedule", schedule)
                .put("counts", DataObject.empty().put("channels", counts.channels()).put("categories", counts.categories()).put("roles", counts.roles()).put("chosenChannels", counts.chosenChannels())
                        .put("privateChannels", wrapupOps.compressibleCount(id)))
                .put("job", jobJson).put("export", exportJson);
    }

    private static String iso(Instant at) {
        return at == null ? null : at.toString();
    }
}
