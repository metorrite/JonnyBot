package com.younglings.bot.tracking;

/**
 * The fixed set of announcement groups an admin enables and picks destinations for from
 * {@code /configure}'s Tracking panel — approved as a taxonomy before this was built (RuneMetrics'
 * own Adventurer's Log categories, Clan Citadel, the clan roster diff, and Discord's own admin log,
 * grouped from JDA's {@code ActionType} list).
 * <p>
 * Deliberately code, not a database table an admin defines from scratch: which entry types belong to
 * which group is a judgment call made once, not something worth 150+ individual toggles for. What IS
 * per-guild configurable — whether a group is enabled, and which channel(s)/thread(s) it posts to —
 * lives in {@link TrackingRepository}.
 */
public enum TrackingGroup {
    SKILL_MILESTONES("RuneMetrics", "Skill Milestones"),
    QUESTS("RuneMetrics", "Quests & Quest Points"),
    NOTABLE_DROPS("RuneMetrics", "Notable Drops"),
    CLUE_SCROLLS("RuneMetrics", "Clue Scrolls & Treasure Trails"),
    PETS("RuneMetrics", "Pets"),
    BOSS_KILLS("RuneMetrics", "Boss & Monster Milestones"),
    MINIGAME_MISC("RuneMetrics", "Minigame & Activity Milestones"),
    ARCHAEOLOGY("RuneMetrics", "Archaeology"),
    CITADEL_ACTIVITY("Clan Citadel", "Citadel Activity"),
    CLAN_JOINS_LEAVES("Clan Roster", "Clan Joins & Leaves"),
    SERVER_SETTINGS("Discord Admin Log", "Server Settings"),
    CHANNELS_THREADS("Discord Admin Log", "Channels & Threads"),
    ROLES_PERMISSIONS("Discord Admin Log", "Roles & Command Permissions"),
    MEMBERS_MODERATION("Discord Admin Log", "Members & Moderation"),
    MESSAGES("Discord Admin Log", "Messages"),
    SERVER_EXTRAS("Discord Admin Log", "Server Extras");

    private final String source;
    private final String displayName;

    TrackingGroup(String source, String displayName) {
        this.source = source;
        this.displayName = displayName;
    }

    /** Which of the four sources (RuneMetrics, Clan Citadel, Clan Roster, Discord Admin Log) feeds this group — for grouping the Tracking panel's display only. */
    public String source() {
        return source;
    }

    public String displayName() {
        return displayName;
    }
}
