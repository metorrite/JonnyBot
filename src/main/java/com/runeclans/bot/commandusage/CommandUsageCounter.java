package com.runeclans.bot.commandusage;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slash-command uses counted in memory between flushes: per server, top-level command name and UTC day. Nothing about who ran a command is kept.
 * {@link #drain()} hands over what has been counted and starts again from zero; {@link #restore(Map)} puts a batch back when saving it failed, so
 * a database hiccup loses nothing. The pure part of the feature, so it is tested without JDA or a database.
 */
public final class CommandUsageCounter {
    /** One row of the usage table. */
    public record Key(long guildId, String command, LocalDate day) {}

    private final ConcurrentHashMap<Key, Integer> counts = new ConcurrentHashMap<>();

    public void record(long guildId, String command, LocalDate day) {
        if (command == null || command.isBlank()) return;
        counts.merge(new Key(guildId, command, day), 1, Integer::sum);
    }

    /** Everything counted since the last drain; the counter is empty afterwards. Safe to call while uses are being recorded. */
    public Map<Key, Integer> drain() {
        Map<Key, Integer> out = new HashMap<>();
        for (Key key : counts.keySet()) {
            Integer n = counts.remove(key);
            if (n != null) out.merge(key, n, Integer::sum);
        }
        return out;
    }

    /** Adds a batch back (a flush that failed), merging with anything counted in the meantime. */
    public void restore(Map<Key, Integer> batch) {
        batch.forEach((key, n) -> counts.merge(key, n, Integer::sum));
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }
}
