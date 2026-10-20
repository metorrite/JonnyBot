package com.younglings.bot.hub;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The slash commands that stand on their own rather than belonging to one feature, each shown as a card on the dashboard's
 * Hub. {@code slashName} is the command's top-level name in Discord. {@code defaultAccess} is plain-English for who may use
 * it when the server hasn't changed anything, and {@code adminByDefault} is whether that default is "Admins only" (which is
 * enforced here, so a server can widen it) as opposed to a rule the command checks itself.
 * <p>
 * To add a command to the Hub, add it here and give it a card on the website; the generic settings (on/off, who may use it,
 * where it may be used) then apply to it with no further code.
 */
public enum HubCommand {
    SIGNUP("signup", "Signups", "signup", "Create and manage signups: queues, groups and submission forms, with a public panel members use.",
            "Admins only", true),
    POLL("poll", "Polls", "poll", "Create a poll, or end one you started.",
            "Linked clan members", false),
    WRAPPED("wrapped", "Recap card", "wrapped", "A member's recap card for a week, month, year or all time: XP, Citadel, boss kills and more.",
            "Everyone", false),
    COMBAT_ACHIEVEMENTS("ca", "Combat achievements", "ca", "Look up a Combat Mastery achievement: its tier, scores and the wiki's tips.",
            "Everyone", false),
    REMINDERS("remindme", "Reminders", "remindme", "Personal reminders: be pinged by DM after a length of time you choose, with a list and cancel.",
            "Everyone", false),
    AFK("afk", "Away status", "afk", "Mark yourself away: people who mention you are told, and it clears when you next speak.",
            "Everyone", false),
    SPLIT("split", "Loot split", "split", "Divide a drop between a group, with an optional fee, and show each person's share.",
            "Everyone", false),
    SUGGEST("suggest", "Suggestions", "suggest", "Members post ideas in the suggestions channel for everyone to vote on; staff reply from the dashboard.",
            "Everyone", false),
    WARN("warn", "Warnings", "warn", "Warn members and keep a record of it: add, list and remove warnings. Members are told by DM.",
            "Admins only", true),
    POINTS("points", "Points", "points", "A server's points ledger: check your balance, see the top members, and staff award points with a reason on record.",
            "Everyone (awarding needs Manage Server)", false),
    ANNOUNCE("announce", "Announce", "announce", "Staff post an announcement in a channel, optionally pinging one role, @everyone or @here. Pings follow the sender's own mention permissions. The dashboard has the same form under Announcements.",
            "Admins only", true),
    RANK("rank", "Self roles", "rank", "List, join and leave the roles this server lets members pick for themselves (the list is edited on the website).",
            "Everyone", false),
    LOCK("lock", "Lock a channel", "lock", "Stop everyone but chosen roles from talking in a channel. /unlock puts its permissions back exactly as they were.",
            "Admins only", true),
    UNLOCK("unlock", "Unlock a channel", "unlock", "Put a channel locked with /lock back exactly as it was.",
            "Admins only", true),
    LOCKDOWN("lockdown", "Lockdown", "lockdown", "Lock or unlock the server's saved set of channels at once (the set is chosen on the Lockdown page).",
            "Admins only", true),
    SERVER_REMINDER("serverreminder", "Server reminders", "serverreminder", "Staff reminders posted in a channel, once or repeating, with an optional role ping (managed on the Server reminders page too).",
            "Admins only", true),
    EVENTS("events", "Events", "events", "See the events you are in (place, points, buy-in, prizes), browse the ones open for signup, and join or leave them. Events are made and run from the dashboard's Event manager.",
            "Everyone", false);

    private final String key;
    private final String title;
    private final String slashName;
    private final String description;
    private final String defaultAccess;
    private final boolean adminByDefault;

    HubCommand(String key, String title, String slashName, String description, String defaultAccess, boolean adminByDefault) {
        this.key = key;
        this.title = title;
        this.slashName = slashName;
        this.description = description;
        this.defaultAccess = defaultAccess;
        this.adminByDefault = adminByDefault;
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public String slashName() {
        return slashName;
    }

    public String description() {
        return description;
    }

    public String defaultAccess() {
        return defaultAccess;
    }

    public boolean adminByDefault() {
        return adminByDefault;
    }

    public static Optional<HubCommand> ofKey(String key) {
        return Arrays.stream(values()).filter(c -> c.key.equals(key)).findFirst();
    }

    public static Optional<HubCommand> ofSlashName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(c -> c.slashName.equals(lower)).findFirst();
    }
}
