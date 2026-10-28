package com.runeclans.bot.commands.coffer;

import java.time.OffsetDateTime;

public record CofferGiveaway(
        long giveawayId,
        long guildId,
        long givenByDiscordId,
        long recipientDiscordId,
        long amount,
        String description,
        OffsetDateTime givenAt
) {}
