package com.younglings.bot.commands.coffer;

import java.time.OffsetDateTime;

public record CofferTransfer(
        long transferId,
        long guildId,
        long fromDiscordId,
        long toDiscordId,
        long amount,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt  // null when still pending
) {}
