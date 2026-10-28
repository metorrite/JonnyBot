package com.runeclans.bot.ticket;

/**
 * The optional extras of the help tickets (PvM Help and CA Help), every one off until a server switches it on, kept apart from the main help settings so each
 * can be added without touching them. Edited on the dashboard's PvM Help page.
 *
 * @param eventButton   a "Make it an event" button on help tickets: a helper or staff member turns the ticket into a boss-night event with signups open
 * @param eventStartHours hours from now until that event is planned to start
 * @param eventDays     how many days that event runs
 * @param relatedTickets when a ticket names the same group boss as another open one, show helpers and staff the other tickets with buttons to link them or merge into one
 * @param groupBosses   the bosses that count as group content for that, comma separated (matched ignoring case against the boss a ticket names)
 * @param priorityTiers help tickets are ranked by who asks: clan members, then longtime guests, then new guests, each with its own pings and escalation
 * @param longtimeGuestDays a guest who has been in the server this many days counts as a longtime guest
 * @param longtimeGuestPings whether a longtime guest's ticket pings the helpers who opted in for its tier as it opens
 * @param longtimeGuestEscalationHours hours before a longtime guest's ticket pings the next role up, 0 for never
 * @param kcRequirements kill counts a new guest needs for a Combat Achievement tier, like "Master=50, Grandmaster=100" (empty for none); tracked kills are read when the guest has a linked
 *                       RuneScape name, and a guest without enough has to describe their earlier attempts instead
 */
public record HelpExtras(long guildId, boolean eventButton, int eventStartHours, int eventDays, boolean relatedTickets, String groupBosses,
                         boolean priorityTiers, int longtimeGuestDays, boolean longtimeGuestPings, int longtimeGuestEscalationHours, String kcRequirements) {
    public static final int MAX_LONGTIME_DAYS = 3650;
    public static final int MAX_ESCALATION_HOURS = 720;
    public static final String DEFAULT_GROUP_BOSSES = "Vorago, Solak";
    public static final int MAX_GROUP_BOSSES = 20;
    public static final int MAX_START_HOURS = 24 * 30;
    public static final int MAX_DAYS = 30;

    public static HelpExtras defaults(long guildId) {
        return new HelpExtras(guildId, false, 24, 1, false, DEFAULT_GROUP_BOSSES, false, 30, true, 120, "");
    }

    /** Everything wrong with these settings, one sentence each; empty when they can be saved. */
    public java.util.List<String> problems() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (eventStartHours < 0 || eventStartHours > MAX_START_HOURS) problems.add("The event can start from now up to " + MAX_START_HOURS + " hours away.");
        if (eventDays < 1 || eventDays > MAX_DAYS) problems.add("The event can run for 1 to " + MAX_DAYS + " days.");
        if (groupBossList().size() > MAX_GROUP_BOSSES) problems.add("Name at most " + MAX_GROUP_BOSSES + " group bosses.");
        if (groupBosses == null || groupBosses.length() > 400) problems.add("The group bosses must be 400 characters or fewer.");
        else if (relatedTickets && groupBossList().isEmpty()) problems.add("Name at least one group boss, or switch related tickets off.");
        if (longtimeGuestDays < 1 || longtimeGuestDays > MAX_LONGTIME_DAYS) problems.add("A longtime guest has been in the server for 1 to " + MAX_LONGTIME_DAYS + " days.");
        if (longtimeGuestEscalationHours < 0 || longtimeGuestEscalationHours > MAX_ESCALATION_HOURS) problems.add("The longtime guest escalation wait must be 0 (never) to " + MAX_ESCALATION_HOURS + " hours.");
        if (kcRequirements == null || kcRequirements.length() > 300) problems.add("The kill count requirements must be 300 characters or fewer.");
        else problems.addAll(kcProblems());
        return problems;
    }

    /** What is wrong with the kill count requirements text (each entry is "Tier=number"), one sentence each. */
    public java.util.List<String> kcProblems() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (kcRequirements == null || kcRequirements.isBlank()) return problems;
        for (String part : kcRequirements.split(",")) {
            if (part.isBlank()) continue;
            String[] kv = part.split("=");
            if (kv.length != 2 || kv[0].isBlank() || !kv[1].strip().matches("\\d{1,5}") || Integer.parseInt(kv[1].strip()) < 1) {
                problems.add("\"" + part.strip() + "\" isn't a tier and a kill count like Master=50.");
            }
        }
        return problems;
    }

    /** The kill count a new guest needs for this tier (as a ticket names it), or null for none. */
    public Integer kcRequirement(String tier) {
        if (tier == null || kcRequirements == null) return null;
        for (String part : kcRequirements.split(",")) {
            String[] kv = part.split("=");
            if (kv.length == 2 && kv[0].strip().equalsIgnoreCase(tier.strip()) && kv[1].strip().matches("\\d{1,5}")) return Integer.parseInt(kv[1].strip());
        }
        return null;
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
