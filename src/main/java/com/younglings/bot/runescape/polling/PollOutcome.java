package com.younglings.bot.runescape.polling;

import com.younglings.bot.runescape.ProfileResult;
import com.younglings.bot.runescape.RuneScapeProfile;

import java.util.Optional;

/**
 * How a {@link PollRequest} ended.
 *
 * @param status   what happened to the request
 * @param result   what RuneMetrics said; present only when a request actually went out ({@link Status#POLLED}, or
 *                 {@link Status#RATE_LIMITED} with the last 429)
 * @param attempts how many requests it took (more than one when it was rate-limited and retried)
 */
public record PollOutcome(Status status, ProfileResult result, int attempts) {
    public enum Status {
        /** A request went out and RuneMetrics answered (with a profile, or with private / unknown / unavailable). */
        POLLED,
        /** Not sent: the player was polled recently enough that the stored snapshot already answers it. */
        REUSED_RECENT,
        /** RuneMetrics kept answering 429 until the retries ran out. */
        RATE_LIMITED,
        /** The poll threw before it got an answer. */
        FAILED,
        /** The coordinator shut down first. */
        CANCELLED,
        /** The caller stopped waiting; the poll itself carries on in the queue. */
        TIMED_OUT
    }

    static PollOutcome polled(ProfileResult result, int attempts) {
        return new PollOutcome(Status.POLLED, result, attempts);
    }

    static PollOutcome reused() {
        return new PollOutcome(Status.REUSED_RECENT, null, 0);
    }

    static PollOutcome rateLimited(ProfileResult result, int attempts) {
        return new PollOutcome(Status.RATE_LIMITED, result, attempts);
    }

    public static PollOutcome failed(int attempts) {
        return new PollOutcome(Status.FAILED, null, attempts);
    }

    public static PollOutcome cancelled() {
        return new PollOutcome(Status.CANCELLED, null, 0);
    }

    public static PollOutcome timedOut() {
        return new PollOutcome(Status.TIMED_OUT, null, 0);
    }

    /** Whether the player's stored data is now current: freshly fetched, or recent enough that fetching was unnecessary. */
    public boolean hasFreshData() {
        return status == Status.REUSED_RECENT || (status == Status.POLLED && result instanceof ProfileResult.Found);
    }

    /** The fetched profile, when this request fetched one. */
    public Optional<RuneScapeProfile> profile() {
        return result instanceof ProfileResult.Found(var profile) ? Optional.of(profile) : Optional.empty();
    }

    /** The RuneMetrics answer for callers that render one: a 429 stays a 429, anything without an answer is "unavailable". */
    public ProfileResult resultOrUnavailable() {
        return result != null ? result : new ProfileResult.Unavailable();
    }
}
