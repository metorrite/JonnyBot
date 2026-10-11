package com.younglings.bot.retention;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deleting everything a server left behind, one table at a time. A table can refuse (another table still points at its rows), so
 * the tables are tried in rounds: each round deletes what it can, and the loop stops when a whole round makes no progress. What
 * is still refusing then is reported rather than forced, and the next sweep tries again. The pure part of the retention feature,
 * so the ordering logic can be tested without a database.
 */
public final class GuildPurge {
    private GuildPurge() {}

    /** Deletes one server's rows from one table and says how many there were; throws if the database refuses. */
    @FunctionalInterface
    public interface RowDeleter {
        int delete(String table, long guildId) throws SQLException;
    }

    /** What a purge did: rows removed per table, and the tables that still refused (with why). */
    public record Result(Map<String, Integer> removed, Map<String, String> refused) {
        public boolean complete() {
            return refused.isEmpty();
        }

        public int totalRows() {
            return removed.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** Tables that must never be purged, even though they have a server id: the lists of trusted and premium servers, and the bookkeeping for this feature. */
    public static final Set<String> KEEP = Set.of("guild_departure", "beta_guild", "premium_guild", "beta_guild_seeded");

    public static Result purge(List<String> tables, long guildId, RowDeleter deleter) {
        Map<String, Integer> removed = new LinkedHashMap<>();
        Map<String, String> refused = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>(tables.stream().filter(t -> !KEEP.contains(t)).toList());

        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            refused.clear();
            for (String table : new ArrayList<>(pending)) {
                try {
                    int rows = deleter.delete(table, guildId);
                    removed.merge(table, rows, Integer::sum);
                    pending.remove(table);
                    progress = true;
                } catch (SQLException e) {
                    refused.put(table, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            }
        }
        if (pending.isEmpty()) refused.clear();
        return new Result(removed, refused);
    }
}
