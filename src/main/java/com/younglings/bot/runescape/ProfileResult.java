package com.younglings.bot.runescape;

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

    /** The request failed outright (network error, non-2xx status, unrecognized error code, etc). */
    record Unavailable() implements ProfileResult {}
}
