package com.runeclans.bot.internal;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.events.EventListing;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.HubModerationRepository;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.notice.BotOwners;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Moderation of the events hub (T-218, design {@code docs/EVENTS-HUB.md} section 5), for the bot's owner only, beside the clan directory's
 * ({@link ClanListingsAdminApi}). Anyone else gets {@code canEdit: false} and nothing else. Reached through {@link TicketAdminApi}, which checks the asker and
 * audit-logs every change.
 * <ul>
 *   <li>{@code GET hub-moderation}: open reports, events taken off the hub, barred servers</li>
 *   <li>{@code POST hub-moderation/events/{id}/unlist} {@code {reason}} and {@code .../relist}</li>
 *   <li>{@code POST hub-moderation/reports/{id}/resolve} {@code {status: DISMISSED|ACTIONED}}</li>
 *   <li>{@code POST hub-moderation/servers/{guildId}/bar} {@code {reason}} and {@code .../lift}</li>
 * </ul>
 */
@BService
public class HubModerationAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(HubModerationAdminApi.class);
    static final int MAX_REASON = 300;

    private final HubModerationRepository moderation;
    private final EventRepository events;
    private final BotOwners owners;

    public HubModerationAdminApi(HubModerationRepository moderation, EventRepository events, BotOwners owners) {
        this.moderation = moderation;
        this.events = events;
        this.owners = owners;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("hub-moderation")) return null;
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return view(guild, actor);
        }
        if (parts.length != 4) return null;
        AdminRouteExtension.requireMethod(method, "POST");
        requireOwner(guild, actor);
        long id = parseId(parts[2]);
        switch (parts[1] + "/" + parts[3]) {
            case "events/unlist" -> unlist(actor, id, reasonOf(body.get(), "The host will see it."));
            case "events/relist" -> relist(actor, id);
            case "reports/resolve" -> {
                String status = body.get().getString("status", "").strip().toUpperCase(Locale.ROOT);
                if (!status.equals("DISMISSED") && !status.equals("ACTIONED")) throw new ApiError(400, Text.of(guild, "dashboard.close_a_report_as_dismissed_or"));
                if (!moderation.resolve(id, status, actor.getIdLong())) throw new ApiError(404, Text.of(guild, "dashboard.that_report_isn_t_open_any"));
                log.info("Dashboard: {} closed hub event report {} as {}", actor.getId(), id, status);
            }
            case "servers/bar" -> {
                moderation.bar(id, reasonOf(body.get(), "Only you see it."), actor.getIdLong());
                log.info("Dashboard: {} barred server {} from listing events on the hub", actor.getId(), id);
            }
            case "servers/lift" -> {
                if (!moderation.lift(id)) throw new ApiError(404, Text.of(guild, "dashboard.that_server_isn_t_barred"));
                log.info("Dashboard: {} let server {} list events on the hub again", actor.getId(), id);
            }
            default -> {
                return null;
            }
        }
        return view(guild, actor);
    }

    private DataObject view(Guild guild, Member actor) {
        boolean owner = owners.isOwner(guild.getJDA(), actor.getIdLong());
        DataArray reports = DataArray.empty();
        DataArray takenDown = DataArray.empty();
        DataArray barred = DataArray.empty();
        if (owner) {
            for (HubModerationRepository.Report r : moderation.openReports()) {
                Event e = events.eventById(r.eventId()).orElse(null);
                reports.add(DataObject.empty().put("id", Long.toString(r.id())).put("eventId", Long.toString(r.eventId())).put("guildId", Long.toString(r.guildId()))
                        .put("serverName", serverName(guild, r.guildId())).put("eventName", e == null ? "(deleted event)" : e.name()).put("reason", r.reason())
                        .put("reporter", r.reporter()).put("createdAt", r.createdAt().toString()).put("takenDown", e != null && EventListing.takenDown(DataObject.fromJson(e.config()))));
            }
            for (Event e : events.sharedBeyondServer(500)) {
                DataObject config = DataObject.fromJson(e.config());
                if (!EventListing.takenDown(config)) continue;
                takenDown.add(DataObject.empty().put("eventId", Long.toString(e.id())).put("eventName", e.name()).put("guildId", Long.toString(e.guildId()))
                        .put("serverName", serverName(guild, e.guildId())).put("reason", EventListing.takedownReason(config)));
            }
            for (HubModerationRepository.Barred b : moderation.barred()) {
                barred.add(DataObject.empty().put("guildId", Long.toString(b.guildId())).put("serverName", serverName(guild, b.guildId())).put("reason", b.reason())
                        .put("at", b.at().toString()));
            }
        }
        return DataObject.empty().put("canEdit", owner).put("reports", reports).put("takenDown", takenDown).put("barred", barred);
    }

    private void unlist(Member actor, long eventId, String reason) {
        Event e = events.eventById(eventId).orElseThrow(() -> new ApiError(404, Text.of(actor.getGuild(), "dashboard.that_event_doesn_t_exist")));
        DataObject config = DataObject.fromJson(e.config());
        DataObject hub = config.hasKey("hub") && !config.isNull("hub") ? config.getObject("hub") : DataObject.empty();
        hub.put("takedown", DataObject.empty().put("reason", reason).put("at", Instant.now().toString()).put("by", actor.getId()));
        config.put("hub", hub);
        events.setConfig(e.guildId(), e.id(), config.toString());
        moderation.resolveAllFor(eventId, actor.getIdLong());
        log.info("Dashboard: {} took event {} of server {} off the events hub ({})", actor.getId(), eventId, e.guildId(), reason);
    }

    private void relist(Member actor, long eventId) {
        Event e = events.eventById(eventId).orElseThrow(() -> new ApiError(404, Text.of(actor.getGuild(), "dashboard.that_event_doesn_t_exist")));
        DataObject config = DataObject.fromJson(e.config());
        if (!EventListing.takenDown(config)) throw new ApiError(409, Text.of(actor.getGuild(), "dashboard.that_event_isn_t_taken_down"));
        config.getObject("hub").remove("takedown");
        events.setConfig(e.guildId(), e.id(), config.toString());
        log.info("Dashboard: {} put event {} of server {} back on the events hub", actor.getId(), eventId, e.guildId());
    }

    private static String serverName(Guild any, long guildId) {
        Guild g = Shards.guild(any.getJDA(), guildId);
        return g == null ? null : g.getName();
    }

    private static String reasonOf(DataObject body, String whoSees) {
        String reason = body.getString("reason", "").strip();
        if (reason.isEmpty() || reason.length() > MAX_REASON) throw new ApiError(400, Text.en("dashboard.give_a_reason_in_at_most_2", MAX_REASON, whoSees));
        return reason;
    }

    private void requireOwner(Guild guild, Member actor) {
        if (!owners.isOwner(guild.getJDA(), actor.getIdLong())) throw new ApiError(403, Text.of(guild, "dashboard.only_the_bot_owner_can_moderate_2"));
    }

    private static long parseId(String raw) {
        if (raw == null || !raw.strip().matches("\\d{1,20}")) throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_id"));
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_id"));
        }
    }
}
