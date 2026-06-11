package com.younglings.bot.commands.poll;

public record PollOption(
        long optionId,
        long pollId,
        int optionNumber,
        String label
) {}
