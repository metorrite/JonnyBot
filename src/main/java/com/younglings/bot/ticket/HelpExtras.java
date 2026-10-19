package com.younglings.bot.ticket;

/**
 * The optional extras of the help tickets (PvM Help and CA Help), every one off until a server switches it on, kept apart from the main help settings so each
 * can be added without touching them. Edited on the dashboard's PvM Help page.
 *
 * @param eventButton   a "Make it an event" button on help tickets: a helper or staff member turns the ticket into a boss-night event with signups open
 * @param eventStartHours hours from now until that event is planned to start
 * @param eventDays     how many days that event runs
 * @param relatedTickets when a ticket names the same group boss as another open one, show helpers and staff the other tickets with buttons to link them or merge into one
 * @param groupBosses   the bosses that count as group content for that, comma separated (matched ignoring case against the boss a ticket names)
 */
public record HelpExtras(long guildId, boolean eventButton, int eventStartHours, int eventDays, boolean relatedTickets, String groupBosses) {
    public static final String DEFAULT_GROUP_BOSSES = "Vorago, Solak";
    public static final int MAX_GROUP_BOSSES = 20;
    public static final int MAX_START_HOURS = 24 * 30;
    public static final int MAX_DAYS = 30;

    public static HelpExtras defaults(long guildId) {
        return new HelpExtras(guildId, false, 24, 1, false, DEFAULT_GROUP_BOSSES);
    }

    /** Everything wrong with these settings, one sentence each; empty when they can be saved. */
    public java.util.List<String> problems() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (eventStartHours < 0 || eventStartHours > MAX_START_HOURS) problems.add("The event can start from now up to " + MAX_START_HOURS + " hours away.");
        if (eventDays < 1 || eventDays > MAX_DAYS) problems.add("The event can run for 1 to " + MAX_DAYS + " days.");
        if (groupBossList().size() > MAX_GROUP_BOSSES) problems.add("Name at most " + MAX_GROUP_BOSSES + " group bosses.");
        if (groupBosses == null || groupBosses.length() > 400) problems.add("The group bosses must be 400 characters or fewer.");
        else if (relatedTickets && groupBossList().isEmpty()) problems.add("Name at least one group boss, or switch related tickets off.");
        return problems;
    }

    /** The group bosses, trimmed and without blanks or repeats. */
    public java.util.List<String> groupBossList() {
        if (groupBosses == null) return java.util.List.of();
        return java.util.Arrays.stream(groupBosses.split(",")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    }

    /** Whether {@code boss} (as a ticket names it) is one of the group bosses. */
    public boolean isGroupBoss(String boss) {
        return boss != null && groupBossList().stream().anyMatch(g -> g.equalsIgnoreCase(boss.strip()));
    }
}
