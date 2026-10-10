package com.younglings.bot.runescape;

import java.time.Duration;

/**
 * Outcome of {@link RuneScapeApiClient#fetchProfileResult}. RuneMetrics reports failure as
 * {@code {"error": "..."}} rather than an HTTP status — {@code NO_PROFILE} is confirmed live
 * (a made-up name returns exactly that). {@code PROFILE_PRIVATE} is the value long documented by
 * community RuneMetrics tooling for an account whose Adventurer's Log is set to private, but it
 * hasn't been reproduced live this session — every account tested (including padding out the clan
 * roster) came back public, so there was nothing private to fetch against.
 */
public sealed interface ProfileResult {
    /** A public profile was fetched successfully. */
    record Found(RuneScapeProfile profile) implements ProfileResult {}

    /** {@code error: "PROFILE_PRIVATE"} — the account exists but has hidden its Adventurer's Log. */
    record Private() implements ProfileResult {}

    /** {@code error: "NO_PROFILE"} (verified live) — no RuneMetrics profile for this name, real or not. */
    record NotFound() implements ProfileResult {}

    /**
     * {@code HTTP 429} — the API asked us to back off. Kept distinct from {@link Unavailable} because
     * the right response is different: the poll coordinator slows its whole request rate and
     * puts this RSN back in the queue for a later retry instead of just counting it as a failed poll and
     * moving on at the normal cadence, which would only make the rate limit worse.
     * {@code retryAfter} is the server's own {@code Retry-After} hint, if it sent one parseable as a
     * plain number of seconds; {@code null} otherwise.
     */
    record RateLimited(Duration retryAfter) implements ProfileResult {}

    /** The request failed outright (network error, non-2xx status, unrecognized error code, etc). */
    record Unavailable() implements ProfileResult {}
}
