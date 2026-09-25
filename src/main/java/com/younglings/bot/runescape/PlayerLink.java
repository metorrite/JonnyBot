package com.younglings.bot.runescape;

import java.time.OffsetDateTime;

/**
 * A confirmed RSN <-> Discord account link — see {@code player_link}. {@code lastSelfPollAt} is
 * {@code null} until the linked member has clicked "Poll Now" under {@code /rs} at least once —
 * see {@code PlayerLinkService#canSelfPoll}.
 */
public record PlayerLink(long linkId, long guildId, long discordUserId, String rsn,
                          String verificationMethod, OffsetDateTime verifiedAt, OffsetDateTime lastSelfPollAt) {
}
