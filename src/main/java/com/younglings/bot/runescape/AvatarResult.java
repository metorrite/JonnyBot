package com.younglings.bot.runescape;

/**
 * Outcome of {@link RuneScapeApiClient#fetchAvatarImage}. The avatar endpoint always responds
 * with an HTTP redirect (verified live): to a player-specific {@code avatar.png?id=N} when a
 * custom look exists, or to a shared {@code default_chat.png} placeholder otherwise — the same
 * placeholder is served both for a real account that's never customized its appearance and for an
 * RSN that doesn't exist at all, so those two cases can't be told apart from this endpoint alone.
 */
public sealed interface AvatarResult {
    /** A real, player-specific avatar image was found. */
    record Found(byte[] imageBytes) implements AvatarResult {}

    /** The RSN resolved to the shared default placeholder, not a real customized avatar. */
    record NotCustomized() implements AvatarResult {}

    /** The request failed (network error, non-2xx/3xx status, etc). */
    record Unavailable() implements AvatarResult {}
}
