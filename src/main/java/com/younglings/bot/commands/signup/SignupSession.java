package com.younglings.bot.commands.signup;

public record SignupSession(
        long signupId,
        long guildId,
        String title,
        String notificationMessage,
        Integer maxSignups,
        SignupType type,
        String submissionFields,
        Long groupRoleId
) {}
