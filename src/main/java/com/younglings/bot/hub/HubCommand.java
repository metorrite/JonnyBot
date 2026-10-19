package com.younglings.bot.hub;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The slash commands that stand on their own rather than belonging to one feature, each shown as a card on the dashboard's
 * Hub. {@code slashName} is the command's top-level name in Discord. {@code defaultAccess} is plain-English for who may use
 * it when the server hasn't changed anything, and {@code adminByDefault} is whether that default is "Admins only" (which is
 * enforced here, so a server can widen it) as opposed to a rule the command checks itself.
 * <p>
 * To add a command to the Hub, add it here with at least one {@link CommandTag} and give it a card on the website; the generic settings (on/off, who may use it,
 * where it may be used) then apply to it with no further code.
 */
public enum HubCommand {
    SIGNUP("signup", "Signups", "signup", "Create and manage signups: queues, groups and submission forms, with a public panel members use.",
            "Admins only", true, CommandTag.FUNCTIONAL, CommandTag.CLAN_MANAGEMENT),
    POLL("poll", "Polls", "poll", "Create a poll, or end one you started.",
            "Linked clan members", false, CommandTag.FUNCTIONAL, CommandTag.UTILITY),
    WRAPPED("wrapped", "Recap card", "wrapped", "A member's recap card for a week, month, year or all time: XP, Citadel, boss kills and more.",
            "Everyone", false, CommandTag.RUNESCAPE, CommandTag.MEMBER_USE),
    COMBAT_ACHIEVEMENTS("ca", "Combat achievements", "ca", "Look up a Combat Mastery achievement: its tier, scores and the wiki's tips.",
            "Everyone", false, CommandTag.RUNESCAPE, CommandTag.MEMBER_USE),
    REMINDERS("remindme", "Reminders", "remindme", "Personal reminders: be pinged by DM after a length of time you choose, with a list and cancel.",
            "Everyone", false, CommandTag.UTILITY, CommandTag.MEMBER_USE),
    AFK("afk", "Away status", "afk", "Mark yourself away: people who mention you are told, and it clears when you next speak.",
            "Everyone", false, CommandTag.UTILITY, CommandTag.MEMBER_USE),
    SPLIT("split", "Loot split", "split", "Divide a drop between a group, with an optional fee, and show each person's share.",
            "Everyone", false, CommandTag.RUNESCAPE, CommandTag.UTILITY, CommandTag.MEMBER_USE),
    SUGGEST("suggest", "Suggestions", "suggest", "Members post ideas in the suggestions channel for everyone to vote on; staff reply from the dashboard.",
            "Everyone", false, CommandTag.FUNCTIONAL, CommandTag.MEMBER_USE),
    WARN("warn", "Warnings", "warn", "Warn members and keep a record of it: add, list and remove warnings. Members are told by DM.",
            "Admins only", true, CommandTag.MODERATION, CommandTag.ADMIN),
    POINTS("points", "Points", "points", "A server's points ledger: check your balance, see the top members, and staff award points with a reason on record.",
            "Everyone (awarding needs Manage Server)", false, CommandTag.CLAN_MANAGEMENT, CommandTag.MEMBER_USE),
    RANK("rank", "Self roles", "rank", "List, join and leave the roles this server lets members pick for themselves (the list is edited on the website).",
            "Everyone", false, CommandTag.FUNCTIONAL, CommandTag.MEMBER_USE),
    EVENTS("events", "Events", "events", "See the events you are in (place, points, buy-in, prizes), browse the ones open for signup, and join or leave them. Events are made and run from the dashboard's Event manager.",
            "Everyone", false, CommandTag.EVENTS, CommandTag.MEMBER_USE);

    private final String key;
    private final String title;
    private final String slashName;
    private final String description;
    private final String defaultAccess;
    private final boolean adminByDefault;
    private final List<CommandTag> tags;

    HubCommand(String key, String title, String slashName, String description, String defaultAccess, boolean adminByDefault, CommandTag... tags) {
        this.key = key;
        this.title = title;
        this.slashName = slashName;
        this.description = description;
        this.defaultAccess = defaultAccess;
        this.adminByDefault = adminByDefault;
        this.tags = List.of(tags);
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

    /** What the command is for; never empty (the dashboard's Commands page filters by these). */
    public List<CommandTag> tags() {
        return tags;
    }

    public static Optional<HubCommand> ofKey(String key) {
        return Arrays.stream(values()).filter(c -> c.key.equals(key)).findFirst();
    }

    public static Optional<HubCommand> ofSlashName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(c -> c.slashName.equals(lower)).findFirst();
    }
}
