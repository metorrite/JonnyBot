package com.younglings.bot.events;

import com.younglings.bot.events.EventRepository.Event;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The group finder's rules (T-589): a "quick group" is a light mode of the events system, so its data lives with the events (Drake's q1). A group
 * is one {@code younglings.event} row with the template {@link #TEMPLATE}, taking signups from the moment it is posted, and its members are that
 * event's players ({@code event_participant}, with the role slot they took in {@code group_role}). A member posts "need 2 for Solak at 20:00" with
 * role slots; others take a slot; the poster and staff close it, and it closes by itself {@link #LISTED_AFTER_START} after its start.
 * <p>
 * The full event engine never sees a group: {@link EventService#event} answers "not found" for one, and the event lists leave them out
 * ({@link #NOT_A_GROUP}). Pure, so it can be tested; {@link IllegalArgumentException}s carry a message that is safe to show.
 */
public final class QuickGroups {
    /** The template key a group's event row carries. No {@link EventTemplate} has it, so no event can be made of this kind by the event manager. */
    public static final String TEMPLATE = "quick-group";
    /** SQL that leaves groups out of an event list ({@code e} may be aliased or not: the column name alone is used). */
    public static final String NOT_A_GROUP = " AND template <> '" + TEMPLATE + "'";

    public static final int MAX_ACTIVITY = 60;
    public static final int MAX_NOTE = 200;
    public static final int MAX_ROLE = 24;
    public static final int MAX_ROLES = 6;
    public static final int MAX_PER_ROLE = 20;
    public static final int MAX_PLACES = 40;
    /** How far ahead a group can be planned. */
    public static final Duration MAX_AHEAD = Duration.ofDays(14);
    /** A start a little in the past is "now" (the form was open a while). */
    static final Duration GRACE = Duration.ofMinutes(10);
    /** How long a group stays listed after its start, so people can still find it while it gets going; then it closes by itself. */
    public static final Duration LISTED_AFTER_START = Duration.ofHours(2);
    /** Open groups a server may have at once, and open groups one person may host. */
    public static final int MAX_OPEN_PER_SERVER = 30;
    public static final int MAX_OPEN_PER_HOST = 3;
    public static final int MAX_LINK = 200;

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\p{Cf}]");
    private static final Pattern LINK = Pattern.compile("^https://[a-z0-9-]+(\\.[a-z0-9-]+)+(:\\d{2,5})?(/[A-Za-z0-9._~/-]*)?$");

    /** One role and how many places it has, e.g. "DPS" × 3. */
    public record Slot(String role, int places) {}

    /** A group as asked for, checked. {@code link} is the clan site page it was posted from (for the Discord post), or null. */
    public record Plan(String activity, String note, Instant startsAt, List<Slot> slots, String link) {
        public int places() {
            return slots.stream().mapToInt(Slot::places).sum();
        }
    }

    private QuickGroups() {}

    public static boolean isGroup(Event event) {
        return event != null && TEMPLATE.equals(event.template());
    }

    /** One line of text a member typed: control and invisible formatting characters out, spaces squeezed, trimmed. */
    static String clean(String raw) {
        if (raw == null) return "";
        return CONTROL.matcher(raw).replaceAll(" ").replaceAll("\\s+", " ").strip();
    }

    /**
     * Checks a new group: what it is for (1 to 60 characters), an optional note (up to 200), when it starts (from now, give or take a few minutes,
     * up to 14 days ahead) and its role slots (1 to 6 named roles, each 1 to 20 places and 40 in all; names unique whatever their case). No slots
     * given is one "Any" role of {@code size} places (4 when that is missing too).
     */
    public static Plan check(DataObject body, Instant now) {
        String activity = clean(body.getString("activity", ""));
        if (activity.isEmpty()) throw new IllegalArgumentException("Say what the group is for, e.g. Solak.");
        if (activity.length() > MAX_ACTIVITY) throw new IllegalArgumentException("Keep what the group is for to " + MAX_ACTIVITY + " characters.");
        String note = clean(body.getString("note", ""));
        if (note.length() > MAX_NOTE) throw new IllegalArgumentException("Keep the note to " + MAX_NOTE + " characters.");

        Instant startsAt;
        try {
            startsAt = Instant.parse(body.getString("startsAt", ""));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Choose when the group starts.");
        }
        if (startsAt.isBefore(now.minus(GRACE))) throw new IllegalArgumentException("That time has already passed. Choose a time from now on.");
        if (startsAt.isAfter(now.plus(MAX_AHEAD))) throw new IllegalArgumentException("A group can be planned up to " + MAX_AHEAD.toDays() + " days ahead.");
        if (startsAt.isBefore(now)) startsAt = now;

        List<Slot> slots = new ArrayList<>();
        DataArray raw = body.optArray("slots").orElse(DataArray.empty());
        if (raw.isEmpty()) {
            int size = body.getInt("size", 4);
            if (size < 1 || size > MAX_PER_ROLE) throw new IllegalArgumentException("A group needs 1 to " + MAX_PER_ROLE + " people.");
            slots.add(new Slot("Any", size));
        } else {
            if (raw.length() > MAX_ROLES) throw new IllegalArgumentException("A group can have up to " + MAX_ROLES + " roles.");
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < raw.length(); i++) {
                DataObject one;
                try {
                    one = raw.getObject(i);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("A role slot isn't one.");
                }
                String role = clean(one.getString("role", ""));
                if (role.isEmpty()) throw new IllegalArgumentException("Give every role a name, e.g. DPS.");
                if (role.length() > MAX_ROLE) throw new IllegalArgumentException("Keep role names to " + MAX_ROLE + " characters.");
                if (!seen.add(role.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Each role can be listed once: \"" + role + "\" is there twice.");
                int places;
                try {
                    places = one.getInt("places");
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Say how many places " + role + " has.");
                }
                if (places < 1 || places > MAX_PER_ROLE) throw new IllegalArgumentException(role + " can have 1 to " + MAX_PER_ROLE + " places.");
                slots.add(new Slot(role, places));
            }
        }
        int total = slots.stream().mapToInt(Slot::places).sum();
        if (total > MAX_PLACES) throw new IllegalArgumentException("A group can have up to " + MAX_PLACES + " places in all.");

        String link = body.getString("link", "");
        link = link == null ? "" : link.strip();
        if (link.length() > MAX_LINK || !LINK.matcher(link).matches()) link = null;
        return new Plan(activity, note, startsAt, List.copyOf(slots), link);
    }

    /** The settings stored on the group's event row: its role slots and the page it was posted from. */
    public static String config(Plan plan) {
        DataArray slots = DataArray.empty();
        for (Slot s : plan.slots()) slots.add(DataObject.empty().put("role", s.role()).put("places", s.places()));
        DataObject group = DataObject.empty().put("slots", slots);
        if (plan.link() != null) group.put("link", plan.link());
        return DataObject.empty().put("quickGroup", group).toString();
    }

    /** The role slots kept on a group's event row; a row that has none (or can't be read) is one "Any" role of 4. */
    public static List<Slot> slotsOf(String config) {
        try {
            DataArray raw = DataObject.fromJson(config).getObject("quickGroup").getArray("slots");
            List<Slot> slots = new ArrayList<>();
            for (int i = 0; i < raw.length(); i++) {
                DataObject one = raw.getObject(i);
                slots.add(new Slot(one.getString("role"), one.getInt("places")));
            }
            if (!slots.isEmpty()) return List.copyOf(slots);
        } catch (RuntimeException ignored) {
            // fall through to the default
        }
        return List.of(new Slot("Any", 4));
    }

    /** The clan site page a group was posted from, or null. */
    public static String linkOf(String config) {
        try {
            String link = DataObject.fromJson(config).getObject("quickGroup").getString("link", null);
            return link != null && LINK.matcher(link).matches() ? link : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The slot named {@code role} (any case), or null. */
    public static Slot slotNamed(List<Slot> slots, String role) {
        String wanted = clean(role).toLowerCase(Locale.ROOT);
        return slots.stream().filter(s -> s.role().toLowerCase(Locale.ROOT).equals(wanted)).findFirst().orElse(null);
    }

    /** How many members hold each role, in the slots' order (a member whose role is no longer a slot is not counted). */
    public static Map<String, Integer> taken(List<Slot> slots, List<String> rolesHeld) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Slot s : slots) out.put(s.role(), 0);
        for (String held : rolesHeld) {
            Slot slot = held == null ? null : slotNamed(slots, held);
            if (slot != null) out.merge(slot.role(), 1, Integer::sum);
        }
        return out;
    }

    /** Whether a role still has a free place. */
    public static boolean hasRoom(Slot slot, int taken) {
        return taken < slot.places();
    }

    /** When a group stops being listed and closes by itself. */
    public static Instant endsAt(Instant startsAt) {
        return startsAt.plus(LISTED_AFTER_START);
    }
}
