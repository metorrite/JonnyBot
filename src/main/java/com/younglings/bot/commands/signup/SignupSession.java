package com.younglings.bot.commands.signup;

public class SignupSession {
    private final long signupId;
    private final long guildId;
    private final String title;
    private final String notificationMessage;
    private final Integer maxSignups;

    public SignupSession(
            long signupId,
            long guildId,
            String title,
            String notificationMessage,
            Integer maxSignups
    ) {
        this.signupId = signupId;
        this.guildId = guildId;
        this.title = title;
        this.notificationMessage = notificationMessage;
        this.maxSignups = maxSignups;
    }

    public long getSignupId() {
        return signupId;
    }

    public long getGuildId() {
        return guildId;
    }

    public String getTitle() {
        return title;
    }

    public String getNotificationMessage() {
        return notificationMessage;
    }

    public Integer getMaxSignups() {
        return maxSignups;
    }
}
