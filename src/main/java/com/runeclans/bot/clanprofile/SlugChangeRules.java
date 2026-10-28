package com.runeclans.bot.clanprofile;

import java.time.Duration;
import java.time.Instant;

/**
 * How a clan site's address is changed after it is set (D4). A valid request opens a one-hour edit window: the address stays as it is until the
 * hour ends or the owner locks the change in, and changing the requested address during the hour does not restart the clock. Applying a change
 * starts a 30-day cooldown before the next one. Pure and clock-free (every method is given the time), so it is tested without a database.
 */
public final class SlugChangeRules {
    /** How long a request stays editable before it is applied. */
    public static final Duration WINDOW = Duration.ofHours(1);
    /** How long after a change the next one is refused. */
    public static final Duration COOLDOWN = Duration.ofDays(30);

    private SlugChangeRules() {}

    /** What is stored for one server: the requested address and when its window opened (both null without one), and when the last change was applied. */
    public record State(String pendingSlug, Instant requestedAt, Instant lastChangedAt) {
        public static final State NONE = new State(null, null, null);

        public boolean hasPending() {
            return pendingSlug != null && requestedAt != null;
        }
    }

    /** The state as the website shows it. {@code appliesAt} and {@code cooldownUntil} are null when there is no pending request or no cooldown. */
    public record Status(String pendingSlug, Instant requestedAt, Instant appliesAt, Instant cooldownUntil) {}

    public static Status status(State s, Instant now) {
        Instant applies = s.hasPending() ? s.requestedAt().plus(WINDOW) : null;
        Instant cooldown = s.lastChangedAt() == null ? null : s.lastChangedAt().plus(COOLDOWN);
        return new Status(s.hasPending() ? s.pendingSlug() : null, s.hasPending() ? s.requestedAt() : null, applies,
                cooldown != null && cooldown.isAfter(now) ? cooldown : null);
    }

    /** Whether the request's hour is over, so the address should change now. */
    public static boolean due(State s, Instant now) {
        return s.hasPending() && !s.requestedAt().plus(WINDOW).isAfter(now);
    }

    /** Whether a new request is refused at {@code now} because of the cooldown (the pending request itself is never blocked by it). */
    public static boolean coolingDown(State s, Instant now) {
        return status(s, now).cooldownUntil() != null;
    }

    /** The state after a request for {@code slug}: a new window, or the same window with the new address when one is already open. */
    public static State requested(State s, String slug, Instant now) {
        Instant opened = s.hasPending() && !due(s, now) ? s.requestedAt() : now;
        return new State(slug, opened, s.lastChangedAt());
    }

    /** The state after the pending address was applied at {@code now}. */
    public static State applied(Instant now) {
        return new State(null, null, now);
    }

    /** The state after the owner dropped the pending request (the cooldown is untouched). */
    public static State cancelled(State s) {
        return new State(null, null, s.lastChangedAt());
    }
}
