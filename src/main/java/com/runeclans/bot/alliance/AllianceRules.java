package com.runeclans.bot.alliance;

/**
 * The rules for alliances: a handful of clan servers that trust each other enough to share events between them. Pure, so it can be tested.
 * Joining is always by invitation the other server accepts; nothing is shared with a server that hasn't said yes.
 */
public final class AllianceRules {
    public static final int MAX_MEMBERS = 10;
    public static final int MAX_PER_SERVER = 5;
    public static final int MIN_NAME = 3;
    public static final int MAX_NAME = 40;

    /** Invited: asked, not yet answered. Active: has accepted and sees the alliance's shared events. */
    public enum Status { INVITED, ACTIVE }

    private AllianceRules() {}

    /** What is wrong with an alliance name, or null. Plain words: letters, digits, spaces and a few marks. */
    public static String nameProblem(String name) {
        String n = name == null ? "" : name.strip();
        if (n.length() < MIN_NAME) return "Give the alliance a name of at least " + MIN_NAME + " characters.";
        if (n.length() > MAX_NAME) return "Keep the name to " + MAX_NAME + " characters or fewer.";
        if (!n.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} '&.-]*")) return "The name can use letters, numbers, spaces and ' & . - only.";
        return null;
    }

    /** What is wrong with creating or joining one more alliance, or null. */
    public static String serverLimitProblem(int alliancesAlready) {
        return alliancesAlready >= MAX_PER_SERVER ? "A server can be in " + MAX_PER_SERVER + " alliances (including invitations). Leave or decline one first." : null;
    }

    /** What is wrong with inviting a server, or null. */
    public static String inviteProblem(boolean isSelf, boolean alreadyInvolved, int membersAndInvites) {
        if (isSelf) return "That's your own server.";
        if (alreadyInvolved) return "That clan is already in this alliance or has been invited.";
        if (membersAndInvites >= MAX_MEMBERS) return "An alliance holds at most " + MAX_MEMBERS + " clans, counting invitations.";
        return null;
    }
}
