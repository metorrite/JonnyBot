package com.younglings.bot.plugin;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.Locale;
import java.util.Set;

/** The kinds of event the plugin can report in schema v1, the category each belongs to, and what each must carry. */
public enum PluginEventType {
    DROP(PluginCategory.DROPS), KILL(PluginCategory.KILLS), LEVEL_UP(PluginCategory.ACHIEVEMENTS), QUEST_COMPLETE(PluginCategory.ACHIEVEMENTS),
    ACHIEVEMENT(PluginCategory.ACHIEVEMENTS), COLLECTION_LOG(PluginCategory.ACHIEVEMENTS), PRESENCE(PluginCategory.PRESENCE);

    private static final int MAX_TEXT = 200;
    private static final int MAX_NUMBER = Integer.MAX_VALUE;
    private static final Set<String> PRESENCE_STATES = Set.of("ONLINE", "OFFLINE", "ACTIVITY");

    private final PluginCategory category;

    PluginEventType(PluginCategory category) {
        this.category = category;
    }

    public PluginCategory category() {
        return category;
    }

    /** The event type named by the plugin, or null if it isn't one of ours (matched case-insensitively). */
    public static PluginEventType parse(String text) {
        if (text == null) return null;
        try {
            return valueOf(text.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** What is wrong with this event's {@code data}, or null when it is acceptable. Extra fields are allowed and kept. */
    public String dataProblem(DataObject data) {
        return switch (this) {
            case DROP -> firstProblem(text(data, "itemName", true), integer(data, "itemId", false, 0), integer(data, "quantity", false, 1), longValue(data, "value", false));
            case KILL -> firstProblem(text(data, "bossName", true), integer(data, "killCount", false, 0), integer(data, "durationSeconds", false, 0));
            case LEVEL_UP -> firstProblem(text(data, "skill", true), range(data, "level", 1, 150));
            case QUEST_COMPLETE -> text(data, "quest", true);
            case ACHIEVEMENT -> firstProblem(text(data, "name", true), text(data, "kind", false));
            case COLLECTION_LOG -> text(data, "itemName", true);
            case PRESENCE -> firstProblem(state(data), text(data, "activity", false));
        };
    }

    private static String firstProblem(String... problems) {
        for (String p : problems) if (p != null) return p;
        return null;
    }

    private static String text(DataObject data, String key, boolean required) {
        if (!data.hasKey(key) || data.isNull(key)) return required ? key + " is required" : null;
        if (!(data.opt(key).orElse(null) instanceof String s)) return key + " must be text";
        if (required && s.isBlank()) return key + " is required";
        return s.length() > MAX_TEXT ? key + " is too long" : null;
    }

    private static String integer(DataObject data, String key, boolean required, int min) {
        if (!data.hasKey(key) || data.isNull(key)) return required ? key + " is required" : null;
        if (!(data.opt(key).orElse(null) instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) return key + " must be a whole number";
        return n.doubleValue() < min || n.doubleValue() > MAX_NUMBER ? key + " is out of range" : null;
    }

    private static String longValue(DataObject data, String key, boolean required) {
        if (!data.hasKey(key) || data.isNull(key)) return required ? key + " is required" : null;
        if (!(data.opt(key).orElse(null) instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) return key + " must be a whole number";
        return n.doubleValue() < 0 ? key + " can't be negative" : null;
    }

    private static String range(DataObject data, String key, int min, int max) {
        String problem = integer(data, key, true, min);
        if (problem != null) return problem;
        return data.getInt(key) > max ? key + " is out of range" : null;
    }

    private static String state(DataObject data) {
        if (!data.hasKey("state") || data.isNull("state") || !(data.get("state") instanceof String s)) return "state is required";
        return PRESENCE_STATES.contains(s.strip().toUpperCase(Locale.ROOT)) ? null : "state must be ONLINE, OFFLINE or ACTIVITY";
    }
}
