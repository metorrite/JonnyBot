package com.younglings.bot.runescape;

import java.time.OffsetDateTime;

/** A confirmed RSN <-> Discord account link — see {@code player_link}. */
public record PlayerLink(long linkId, long guildId, long discordUserId, String rsn,
                          String verificationMethod, OffsetDateTime verifiedAt) {
}
