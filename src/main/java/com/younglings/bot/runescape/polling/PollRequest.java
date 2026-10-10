package com.younglings.bot.runescape.polling;

import java.time.Duration;
import java.util.Objects;

/**
 * One request to have a player's RuneMetrics profile polled, saved and announced.
 *
 * @param rsn      the player (case-insensitive: the same name in any case is the same player)
 * @param priority who is waiting, see {@link PollPriority}
 * @param maxAge   how recent an existing snapshot may be for this request to count as already done. A player polled
 *                 more recently than this (by anything: the schedule, a button, another server's request) is not
 *                 polled again. {@link Duration#ZERO} means a real poll is required.
 * @param delay    how long to hold the request back before it becomes eligible, which is how a recurring tier spreads
 *                 its players across a window instead of queueing them all at once
 * @param source   a short label for logs and the status page ("clan players", "update now")
 */
public record PollRequest(String rsn, PollPriority priority, Duration maxAge, Duration delay, String source) {
    public PollRequest {
        Objects.requireNonNull(rsn, "rsn");
        Objects.requireNonNull(priority, "priority");
        maxAge = maxAge == null || maxAge.isNegative() ? Duration.ZERO : maxAge;
        delay = delay == null || delay.isNegative() ? Duration.ZERO : delay;
        source = source == null ? "" : source;
    }

    /** A person is waiting: top of the queue, and a real poll unless one is already running for the same player. */
    public static PollRequest interactive(String rsn, String source) {
        return new PollRequest(rsn, PollPriority.INTERACTIVE, Duration.ZERO, Duration.ZERO, source);
    }

    /** Background work that is satisfied by any snapshot newer than {@code maxAge}. */
    public static PollRequest background(String rsn, PollPriority priority, Duration maxAge, String source) {
        return new PollRequest(rsn, priority, maxAge, Duration.ZERO, source);
    }

    public PollRequest withDelay(Duration delay) {
        return new PollRequest(rsn, priority, maxAge, delay, source);
    }

    public PollRequest withMaxAge(Duration maxAge) {
        return new PollRequest(rsn, priority, maxAge, delay, source);
    }
}
