package com.younglings.bot.ticket;

/**
 * The optional extras of the help tickets (PvM Help and CA Help), every one off until a server switches it on, kept apart from the main help settings so each
 * can be added without touching them. Edited on the dashboard's PvM Help page.
 *
 * @param eventButton   a "Make it an event" button on help tickets: a helper or staff member turns the ticket into a boss-night event with signups open
 * @param eventStartHours hours from now until that event is planned to start
 * @param eventDays     how many days that event runs
 */
public record HelpExtras(long guildId, boolean eventButton, int eventStartHours, int eventDays) {
    public static final int MAX_START_HOURS = 24 * 30;
    public static final int MAX_DAYS = 30;

    public static HelpExtras defaults(long guildId) {
        return new HelpExtras(guildId, false, 24, 1);
    }

    /** Everything wrong with these settings, one sentence each; empty when they can be saved. */
    public java.util.List<String> problems() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (eventStartHours < 0 || eventStartHours > MAX_START_HOURS) problems.add("The event can start from now up to " + MAX_START_HOURS + " hours away.");
        if (eventDays < 1 || eventDays > MAX_DAYS) problems.add("The event can run for 1 to " + MAX_DAYS + " days.");
        return problems;
    }
}
