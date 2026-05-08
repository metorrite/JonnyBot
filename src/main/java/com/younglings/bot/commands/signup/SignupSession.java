package com.younglings.bot.commands.signup;

import com.younglings.bot.commands.signup.SignupEntry;

import java.util.LinkedList;

public class SignupSession {
    private final String title;
    private final String notificationMessage;
    private final Integer maxSignups;
    private final long publicChannelId;
    private final long adminChannelId;
    private long publicMessageId;
    private long adminMessageId;
    private final LinkedList<SignupEntry> entries = new LinkedList<>();

    public SignupSession(String title, String notificationMessage, Integer maxSignups, long publicChannelId, long adminChannelId) {
        this.title = title;
        this.notificationMessage = notificationMessage;
        this.maxSignups = maxSignups;
        this.publicChannelId = publicChannelId;
        this.adminChannelId = adminChannelId;
    }

    public boolean containsUser(long userId) {
        return entries.stream().anyMatch(entry -> entry.userId() == userId);
    }

    public boolean containsUsername(String username) {
        return entries.stream().anyMatch(entry -> entry.username().equalsIgnoreCase(username));
    }

    public String getTitle() { return title; }
    public String getNotificationMessage() { return notificationMessage; }
    public Integer getMaxSignups() { return maxSignups; }
    public long getPublicChannelId() { return publicChannelId; }
    public long getAdminChannelId() { return adminChannelId; }
    public long getPublicMessageId() { return publicMessageId; }
    public long getAdminMessageId() { return adminMessageId; }
    public LinkedList<SignupEntry> getEntries() { return entries; }

    public void setPublicMessageId(long publicMessageId) { this.publicMessageId = publicMessageId; }
    public void setAdminMessageId(long adminMessageId) { this.adminMessageId = adminMessageId; }
}
