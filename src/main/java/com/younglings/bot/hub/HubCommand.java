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
    HIGHLIGHT("highlight", "Highlights", "highlight", "Members pick words and get a direct message when someone says one in a channel they can see. Off until the Highlights page switches it on.",
            "Everyone", false),
    EVENTS("events", "Events", "events", "See the events you are in (place, points, buy-in, prizes), browse the ones open for signup, and join or leave them. Events are made and run from the dashboard's Event manager.",
            "Everyone", false),
    LEVEL("level", "Chat level", "level", "See your chat level, XP and rank, or another member's. Chat levels are off until the Chat levels page switches them on.",
            "Everyone", false),
    LEVELS("levels", "Chat leaderboard", "levels", "The most active chatters in the server.",
            "Everyone", false),
    PRICE("price", "Grand Exchange prices", "price", "Look up an item's Grand Exchange price with its 7 and 30 day change, and a link to its wiki page.",
            "Everyone", false),
    WORLD_EVENTS("worldevents", "World events", "worldevents", "The Voice of Seren right now and the events that come round by themselves, with how often. Hourly reminders are set on the RuneScape timers page.",
            "Everyone", false),
    RESET("reset", "Reset timers", "reset", "Countdowns to the daily, weekly and monthly RuneScape resets. Reminders at each reset are set on the RuneScape timers page.",
            "Everyone", false),
    DND("dnd", "Distractions and Diversions", "dnd", "The RuneScape Distractions and Diversions by how often they can be played (daily, weekly, monthly, when available), from the wiki.",
            "Everyone", false),
    BOSS("boss", "Boss info", "boss", "A short wiki summary of a RuneScape 3 boss with its key facts and a link to its strategy page.",
            "Everyone", false),
    QUEST("quest", "Quest info", "quest", "A short wiki summary of a RuneScape 3 quest with its difficulty, area and a link to its quick guide.",
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
