package com.runeclans.bot.commands.signup;

public record SignupMessage(
        long messageId,
        long signupId,
        long guildId,
        long channelId,
        String messageType
) {
}
