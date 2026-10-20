package com.younglings.bot.events;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A server's own starting point for new events (T-208): the channel and role name patterns, the bingo look, when players must be verified by and whether
 * the setup steps are followed. New events copy these in place of the built-in standard values; an event that already exists never changes, and a value a
 * server never set leaves the built-in one, so a server that never opens the settings gets exactly what it always did.
 *
 * @param names      patterns by {@link EventSpace.Kind} or {@link EventSpace.RoleName} key; only the ones the server changed
 * @param look       a {@link BingoLooks} id, or null
 * @param gate       an {@link EventRoster.Gate} name, or null
 * @param setupMode  {@code STANDARD} or {@code OFF}, or null
 */
public record EventDefaults(Map<String, String> names, String look, String gate, String setupMode, String inviteUrl) {
    public static final EventDefaults NONE = new EventDefaults(Map.of(), null, null, null, null);
    public static final int MAX_PATTERN = 100;
    /** A Discord invite: what the events hub shows someone who has to join the host server first (T-216). */
    private static final java.util.regex.Pattern INVITE = java.util.regex.Pattern.compile("https://(discord\\.gg|(www\\.)?discord\\.com/invite)/[A-Za-z0-9-]{2,32}/?");

    public EventDefaults {
        names = names == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(names));
    }

    public EventDefaults(Map<String, String> names, String look, String gate, String setupMode) {
        this(names, look, gate, setupMode, null);
    }

    /** Every name pattern a server can set: the channels, then the roles, each with its label and built-in pattern. */
    public record NameKey(String key, String label, String builtIn) {}

    public static List<NameKey> nameKeys() {
        List<NameKey> keys = new ArrayList<>();
        for (EventSpace.Kind k : EventSpace.Kind.values()) keys.add(new NameKey(k.key(), k.label(), k.defaultPattern()));
        for (EventSpace.RoleName r : EventSpace.RoleName.values()) keys.add(new NameKey(r.key(), r.label(), r.defaultPattern()));
        return keys;
    }

    private static boolean isNameKey(String key) {
        return nameKeys().stream().anyMatch(k -> k.key().equals(key));
    }

    /**
     * Reads what the dashboard sent, refusing anything that isn't a setting or isn't allowed. Blank values mean "use the built-in one".
     *
     * @throws IllegalArgumentException with words for the person who sent it
     */
    public static EventDefaults parse(DataObject body) {
        Map<String, String> names = new LinkedHashMap<>();
        if (body.hasKey("names") && !body.isNull("names")) {
            DataObject n = body.getObject("names");
            for (String key : n.keys()) {
                if (!isNameKey(key)) throw new IllegalArgumentException("There is no channel or role called \"" + key + "\" to name.");
                if (n.isNull(key)) continue;
                String pattern = n.getString(key).strip();
                if (pattern.isEmpty()) continue;
                if (pattern.length() > MAX_PATTERN) throw new IllegalArgumentException("A name pattern can be at most " + MAX_PATTERN + " characters.");
                names.put(key, pattern);
            }
        }
        String look = text(body, "look");
        if (look != null && !BingoLooks.valid(look)) throw new IllegalArgumentException("Choose a board look from the list.");
        String gate = text(body, "gate");
        if (gate != null) {
            gate = gate.toUpperCase(Locale.ROOT);
            try {
                EventRoster.Gate.valueOf(gate);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Choose when players must be verified by: when they sign up, before teams, or before the start.");
            }
        }
        String mode = text(body, "setupMode");
        if (mode != null) {
            mode = mode.toUpperCase(Locale.ROOT);
            if (!mode.equals(EventPrep.Mode.STANDARD.name()) && !mode.equals(EventPrep.Mode.OFF.name())) {
                throw new IllegalArgumentException("Setup steps are either followed or not (STANDARD or OFF).");
            }
        }
        String invite = text(body, "inviteUrl");
        if (invite != null && !INVITE.matcher(invite).matches()) throw new IllegalArgumentException("The invite link has to be a Discord invite, like https://discord.gg/abc123.");
        return new EventDefaults(names, look, gate, mode, invite);
    }

    /** The same, from what was saved; anything that no longer reads is dropped rather than breaking event creation. */
    public static EventDefaults fromStored(String json) {
        if (json == null || json.isBlank()) return NONE;
        try {
            return parse(DataObject.fromJson(json));
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    private static String text(DataObject o, String key) {
        if (!o.hasKey(key) || o.isNull(key)) return null;
        String s = o.getString(key).strip();
        return s.isEmpty() ? null : s;
    }

    public boolean isEmpty() {
        return names.isEmpty() && look == null && gate == null && setupMode == null && inviteUrl == null;
    }

    public DataObject json() {
        DataObject n = DataObject.empty();
        names.forEach(n::put);
        DataObject o = DataObject.empty().put("names", n);
        if (look != null) o.put("look", look);
        if (gate != null) o.put("gate", gate);
        if (setupMode != null) o.put("setupMode", setupMode);
        if (inviteUrl != null) o.put("inviteUrl", inviteUrl);
        return o;
    }

    /** Lays these defaults over a new event's standard answers for {@code template}. Only what the server set is touched. */
    public void applyTo(DataObject answers, EventTemplate template) {
        names.forEach((key, pattern) -> EventOptions.set(answers, "config.channels.names." + key, pattern));
        if (look != null && BingoTemplate.KEY.equals(template.key())) EventOptions.set(answers, "config.board.look", look);
        if (gate != null) EventOptions.set(answers, "config.verification.gate", gate);
        if (setupMode != null) EventOptions.set(answers, "config.setup.mode", setupMode);
    }
}
