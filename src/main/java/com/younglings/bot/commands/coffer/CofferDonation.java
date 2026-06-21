package com.younglings.bot.commands.coffer;

import java.time.OffsetDateTime;

public record CofferDonation(
        long donationId,
        long guildId,
        String donorName,
        long amount,
        long submittedByDiscordId,
        OffsetDateTime submittedAt
) {}
