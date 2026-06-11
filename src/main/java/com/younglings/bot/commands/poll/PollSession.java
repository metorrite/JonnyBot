package com.younglings.bot.commands.poll;

public record PollSession(
        long pollId,
        long guildId,
        long channelId,
        Long messageId,
        String title,
        boolean anonymous,
        boolean multipleVotes,
        String status
) {}
