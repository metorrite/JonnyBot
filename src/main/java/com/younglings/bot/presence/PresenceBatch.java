package com.younglings.bot.presence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The members to load because they came online while the bot didn't have them cached ({@link PresenceMemberLoader}): per server, in the
 * order they came, each asked for at most once per {@link #COOLDOWN_MS} (someone flicking between online and idle is asked for once).
 * Not thread-safe on its own; the loader synchronises on it.
 */
final class PresenceBatch {
    /** How long a member who was asked for isn't asked for again. */
    static final long COOLDOWN_MS = 5 * 60_000L;
    /** The most member ids one gateway request takes. */
    static final int MAX_PER_REQUEST = 100;

    private final Map<Long, LinkedHashSet<Long>> pending = new HashMap<>();
    private final Map<Long, Long> askedAt = new HashMap<>();

    /**
     * Notes a member to load. True when this is the first waiting member of its server (the caller schedules that server's flush); false
     * when the server already has a flush on the way, or the member was asked for within the cooldown.
     */
    boolean add(long guildId, long userId, long now) {
        Long last = askedAt.get(userId);
        if (last != null && now - last < COOLDOWN_MS) return false;
        LinkedHashSet<Long> waiting = pending.computeIfAbsent(guildId, g -> new LinkedHashSet<>());
        boolean first = waiting.isEmpty();
        waiting.add(userId);
        return first;
    }

    /** The server's waiting members, in requests of at most {@link #MAX_PER_REQUEST}; they count as asked for from {@code now}. */
    List<List<Long>> drain(long guildId, long now) {
        Set<Long> waiting = pending.remove(guildId);
        List<List<Long>> requests = new ArrayList<>();
        if (waiting == null) return requests;
        List<Long> current = new ArrayList<>();
        for (Long id : waiting) {
            askedAt.put(id, now);
            current.add(id);
            if (current.size() == MAX_PER_REQUEST) {
                requests.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) requests.add(current);
        // forget old cooldowns so the map doesn't grow for the life of the process
        askedAt.values().removeIf(at -> now - at >= COOLDOWN_MS);
        return requests;
    }
}
