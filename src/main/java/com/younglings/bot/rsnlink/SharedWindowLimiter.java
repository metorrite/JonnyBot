package com.younglings.bot.rsnlink;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@link WindowLimiter}'s "at most N in any window" rule, kept in the link database's {@code rate_window} table so every replica shares
 * one count (SCALING-STATE.md M7). One row per limiter and key holds the times of the hits still inside the window (at most {@code max}
 * of them), so the answer is exactly the in-memory limiter's.
 * <p>
 * <b>One statement per check.</b> {@link #tryAcquire} is a single {@code INSERT ... ON CONFLICT DO UPDATE}: the row lock it takes makes
 * two replicas hitting the same key at the same moment queue up rather than both slip under the cap. The lock is held until the caller's
 * transaction ends, which in {@link LinkService#tx} is the length of one route.
 * <p>
 * <b>Inside the route's transaction.</b> A refusal ({@link LinkException}) commits, so refused and wrong-code hits are kept exactly as
 * before. Only an unexpected error rolls the hit back, together with everything else the route did.
 * <p>
 * <b>If the database fails</b>, the {@link SQLException} propagates and the route answers 500: the action is refused (fail closed). The
 * route needs the same database for its own work, so there is no case where the limit is down but the action could go ahead.
 * <p>
 * Times come from the service's clock (as before), not the database's, so tests can move time. Keys are lookups (hex of an HMAC), never
 * raw values. Old rows are removed by the hourly retention sweep ({@link #sweep}).
 */
final class SharedWindowLimiter {
    private final String name;
    private final int max;
    private final long windowMillis;
    private final String acquireSql;
    private final String countSql;

    SharedWindowLimiter(String schema, String name, LinkLimits.Window window) {
        if (window.max() < 1) throw new IllegalArgumentException("A window needs a max of at least 1");
        this.name = name;
        this.max = window.max();
        this.windowMillis = window.window().toMillis();
        // Parameters: limiter, key, now, now + window | cutoff, max (allowed) | cutoff, max, now, cutoff, cutoff (hits) | cutoff, max, now + window (expires)
        this.acquireSql = """
                INSERT INTO %1$s.rate_window AS w (limiter, key, hits, expires_at, last_allowed) VALUES (?, ?, ARRAY[?::bigint], ?, true)
                ON CONFLICT (limiter, key) DO UPDATE SET
                    last_allowed = (SELECT count(*) FROM unnest(w.hits) h WHERE h > ?) < ?,
                    hits = CASE WHEN (SELECT count(*) FROM unnest(w.hits) h WHERE h > ?) < ?
                        THEN ARRAY(SELECT h FROM unnest(w.hits || ?::bigint) h WHERE h > ? ORDER BY h)
                        ELSE ARRAY(SELECT h FROM unnest(w.hits) h WHERE h > ? ORDER BY h) END,
                    expires_at = CASE WHEN (SELECT count(*) FROM unnest(w.hits) h WHERE h > ?) < ? THEN greatest(w.expires_at, ?) ELSE w.expires_at END
                RETURNING last_allowed, hits[1]
                """.formatted(schema);
        this.countSql = "SELECT count(*) FROM %1$s.rate_window w, unnest(w.hits) h WHERE w.limiter = ? AND w.key = ? AND h > ?".formatted(schema);
    }

    /** Records one action for {@code key} and returns 0, or returns the seconds to wait (and records nothing) if the window is full. */
    int tryAcquire(Connection c, String key, long now) throws SQLException {
        long cutoff = now - windowMillis;
        long expires = now + windowMillis;
        try (PreparedStatement s = c.prepareStatement(acquireSql)) {
            int i = 1;
            s.setString(i++, name);
            s.setString(i++, key);
            s.setLong(i++, now);
            s.setLong(i++, expires);
            s.setLong(i++, cutoff);
            s.setInt(i++, max);
            s.setLong(i++, cutoff);
            s.setInt(i++, max);
            s.setLong(i++, now);
            s.setLong(i++, cutoff);
            s.setLong(i++, cutoff);
            s.setLong(i++, cutoff);
            s.setInt(i++, max);
            s.setLong(i, expires);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                if (rs.getBoolean(1)) return 0;
                return WindowLimiter.waitSeconds(rs.getLong(2), windowMillis, now);
            }
        }
    }

    /** Whether one more would be allowed, without recording it. */
    boolean wouldAllow(Connection c, String key, long now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(countSql)) {
            s.setString(1, name);
            s.setString(2, key);
            s.setLong(3, now - windowMillis);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1) < max;
            }
        }
    }

    /** Deletes the rows whose newest hit has left its window. Returns how many went. */
    static int sweep(Connection c, String schema, long now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("DELETE FROM " + schema + ".rate_window WHERE expires_at <= ?")) {
            s.setLong(1, now);
            return s.executeUpdate();
        }
    }
}
