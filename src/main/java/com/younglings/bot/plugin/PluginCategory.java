package com.younglings.bot.plugin;

/**
 * What a player can choose to let the plugin report. Nothing is stored for a category the player has not switched on, however the
 * plugin is configured, so the choice is enforced here rather than trusted to the client.
 */
public enum PluginCategory {
    XP("Skill XP", "How much XP you have in each skill, so your clan can follow your progress."),
    DROPS("Drops", "Items you receive, and where they came from."),
    KILLS("Boss kills", "Which boss you killed and your kill count."),
    ACHIEVEMENTS("Levels, quests and achievements", "Level-ups, completed quests, achievements and collection log entries."),
    PRESENCE("Online status and activity", "Whether you are online and what you are doing, so clanmates can see who is around. In a team event, "
            + "your team sees in RuneSync whether you are in the game with RuneSync now.");

    private final String label;
    private final String description;

    PluginCategory(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
