package com.younglings.bot.hub;

/**
 * What a Hub command is for. The dashboard's Commands page filters by these, so a server can park niche or fun commands switched off
 * without them cluttering the page. {@code key} is what the admin API sends, {@code label} is what the page shows. Keep the set short:
 * add a tag only when a command fits none of these.
 */
public enum CommandTag {
    FUNCTIONAL("functional", "Functional"),
    FUN("fun", "Fun"),
    ADMIN("admin", "Admin"),
    MODERATION("moderation", "Moderation"),
    RUNESCAPE("runescape", "RuneScape"),
    CLAN_MANAGEMENT("clan-management", "Clan management"),
    MEMBER_USE("member-use", "Member use"),
    EVENTS("events", "Events"),
    UTILITY("utility", "Utility");

    private final String key;
    private final String label;

    CommandTag(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }
}
