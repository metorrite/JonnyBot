package com.younglings.bot.plugin;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads and checks a batch from the plugin (schema v1) without touching the database, so every rule is testable. A bad batch as a whole
 * is refused; a bad event inside a good batch is rejected on its own, with a reason the plugin can show, and the rest still go through.
 * <pre>{@code
 * { "schema": 1, "client": {"name": "...", "version": "..."}, "player": {"rsn": "..."},
 *   "events": [ { "id": "unique-per-event", "type": "DROP", "at": "2026-10-11T12:00:00Z",
 *                 "source": {"kind": "NPC", "name": "Telos", "id": 1234}, "data": {"itemName": "...", "quantity": 1} } ] }
 * }</pre>
 */
public final class PluginPayload {
    public static final int SCHEMA = 1;
    public static final int MAX_EVENTS = 100;
    public static final int MAX_DATA_JSON = 2_000;
    /** An event older than this is stale (the client was offline a long time); newer than a few minutes ahead means its clock is wrong. */
    public static final Duration MAX_AGE = Duration.ofDays(14);
    public static final Duration MAX_FUTURE = Duration.ofMinutes(5);

    private static final Set<String> SOURCE_KINDS = Set.of("NPC", "ACTIVITY", "CHEST", "PLAYER", "OTHER");

    /** One event that passed every check, ready to store. {@code dataJson} is the event's data as JSON text. */
    public record Event(String key, PluginEventType type, Instant at, String sourceKind, String sourceName, Long sourceId, String dataJson) {}

    /** One event that was refused, and why. {@code id} is whatever the plugin called it (null if it didn't say). */
    public record Reject(String id, String reason) {}

    /** The result of reading a batch: either a {@code problem} with the whole batch, or the player, the client and the sorted-out events. */
    public record Parsed(String problem, String rsn, String client, List<Event> events, List<Reject> rejects) {
        static Parsed bad(String problem) {
            return new Parsed(problem, null, null, List.of(), List.of());
        }
    }

    private PluginPayload() {}

    /** A RuneScape display name with Jagex's non-breaking spaces made ordinary, trimmed, or null if it can't be one (1 to 12 letters, digits, spaces, - or _). */
    public static String normaliseRsn(String raw) {
        if (raw == null) return null;
        String name = raw.replace(' ', ' ').strip().replaceAll("\\s+", " ");
        return name.matches("[A-Za-z0-9 _-]{1,12}") ? name : null;
    }

    public static Parsed parse(DataObject body, Instant now) {
        if (body.getInt("schema", -1) != SCHEMA) return Parsed.bad("This server understands schema " + SCHEMA + ". Set \"schema\": " + SCHEMA + ".");
        String rsn = body.hasKey("player") && !body.isNull("player") && body.opt("player").orElse(null) instanceof java.util.Map ? normaliseRsn(body.getObject("player").getString("rsn", null)) : null;
        if (rsn == null) return Parsed.bad("player.rsn must be a RuneScape name of 1 to 12 characters.");
        if (!body.hasKey("events") || body.isNull("events") || !(body.opt("events").orElse(null) instanceof java.util.List)) return Parsed.bad("events must be a list.");
        DataArray raw = body.getArray("events");
        if (raw.isEmpty()) return Parsed.bad("events is empty.");
        if (raw.length() > MAX_EVENTS) return Parsed.bad("A batch holds at most " + MAX_EVENTS + " events. Send smaller batches.");

        String client = "unknown";
        if (body.hasKey("client") && !body.isNull("client") && body.opt("client").orElse(null) instanceof java.util.Map) {
            DataObject c = body.getObject("client");
            String name = c.getString("name", "unknown");
            String version = c.getString("version", "");
            client = (name + (version.isBlank() ? "" : " " + version)).strip();
            if (client.length() > 80) client = client.substring(0, 80);
        }

        List<Event> events = new ArrayList<>();
        List<Reject> rejects = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < raw.length(); i++) {
            if (!raw.isType(i, DataType.OBJECT)) {
                rejects.add(new Reject(null, "event " + i + " isn't an object"));
                continue;
            }
            DataObject e = raw.getObject(i);
            String id = e.hasKey("id") && !e.isNull("id") && e.opt("id").orElse(null) instanceof String s ? s.strip() : null;
            String problem = eventProblem(e, id, now);
            if (problem != null) {
                rejects.add(new Reject(id, problem));
            } else if (!seen.add(id)) {
                rejects.add(new Reject(id, "duplicate_in_batch"));
            } else {
                events.add(toEvent(e, id));
            }
        }
        return new Parsed(null, rsn, client, events, rejects);
    }

    private static String eventProblem(DataObject e, String id, Instant now) {
        if (id == null || !id.matches("[A-Za-z0-9._:-]{8,64}")) return "invalid_id: id must be 8 to 64 characters (letters, digits . _ : -), unique per event";
        PluginEventType type = PluginEventType.parse(e.getString("type", null));
        if (type == null) return "unknown_type";
        Instant at = parseInstant(e.getString("at", null));
        if (at == null) return "invalid_time: at must be an ISO-8601 instant like 2026-10-11T12:00:00Z";
        if (at.isAfter(now.plus(MAX_FUTURE))) return "in_future: the event is dated in the future, check the client's clock";
        if (at.isBefore(now.minus(MAX_AGE))) return "too_old: events older than " + MAX_AGE.toDays() + " days are not accepted";
        if (!e.hasKey("data") || e.isNull("data") || !(e.opt("data").orElse(null) instanceof java.util.Map)) return "invalid_data: data must be an object";
        DataObject data = e.getObject("data");
        String json = new String(data.toJson(), java.nio.charset.StandardCharsets.UTF_8);
        if (json.length() > MAX_DATA_JSON) return "invalid_data: data is larger than " + MAX_DATA_JSON + " characters";
        String dataProblem = type.dataProblem(data);
        if (dataProblem != null) return "invalid_data: " + dataProblem;
        if (e.hasKey("source") && !e.isNull("source")) {
            if (!(e.opt("source").orElse(null) instanceof java.util.Map)) return "invalid_source: source must be an object";
            DataObject s = e.getObject("source");
            String kind = s.getString("kind", "OTHER").strip().toUpperCase(Locale.ROOT);
            if (!SOURCE_KINDS.contains(kind)) return "invalid_source: kind must be one of " + String.join(", ", new java.util.TreeSet<>(SOURCE_KINDS));
            if (s.getString("name", "").length() > 100) return "invalid_source: name is too long";
        }
        return null;
    }

    private static Event toEvent(DataObject e, String id) {
        PluginEventType type = PluginEventType.parse(e.getString("type"));
        String sourceKind = null;
        String sourceName = null;
        Long sourceId = null;
        if (e.hasKey("source") && !e.isNull("source")) {
            DataObject s = e.getObject("source");
            sourceKind = s.getString("kind", "OTHER").strip().toUpperCase(Locale.ROOT);
            sourceName = s.hasKey("name") && !s.isNull("name") ? s.getString("name").strip() : null;
            if (s.hasKey("id") && !s.isNull("id") && s.opt("id").orElse(null) instanceof Number n) sourceId = n.longValue();
        }
        String json = new String(e.getObject("data").toJson(), java.nio.charset.StandardCharsets.UTF_8);
        return new Event(id, type, parseInstant(e.getString("at")), sourceKind, sourceName, sourceId, json);
    }

    static Instant parseInstant(String text) {
        if (text == null) return null;
        try {
            return Instant.parse(text.strip());
        } catch (DateTimeException e) {
            return null;
        }
    }
}
