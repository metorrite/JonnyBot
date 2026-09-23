package com.younglings.bot.runescape;

/** An in-progress (or resolved) makeover-mage verification — see {@code player_verification_attempt}. */
public record VerificationAttempt(long attemptId, long guildId, long discordUserId, String rsn,
                                   String assignedHairstyle, String assignedHairColor,
                                   String assignedSkinTone, String status) {
}
