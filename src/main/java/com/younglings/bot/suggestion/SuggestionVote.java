package com.younglings.bot.suggestion;

/** The rule for the vote buttons: pressing the one you already chose takes your vote back, pressing the other switches it. */
public final class SuggestionVote {
    public static final int UP = 1;
    public static final int DOWN = -1;
    public static final int NONE = 0;

    private SuggestionVote() {}

    /** The vote a person ends up with after pressing {@code pressed} while holding {@code current}. */
    public static int next(int current, int pressed) {
        if (pressed != UP && pressed != DOWN) {
            throw new IllegalArgumentException("pressed must be UP or DOWN");
        }
        return current == pressed ? NONE : pressed;
    }

    public static String buttonId(String direction, long suggestionId) {
        return "sg:" + direction + ":" + suggestionId;
    }

    /** {@code sg:up:12} gives {@code UP}, {@code sg:down:12} gives {@code DOWN}; anything else (other buttons) gives {@link #NONE}. */
    public static int directionOf(String componentId) {
        if (componentId.startsWith("sg:up:")) return UP;
        if (componentId.startsWith("sg:down:")) return DOWN;
        return NONE;
    }

    /** The suggestion a vote button belongs to, or -1 when the id isn't one of ours. */
    public static long suggestionOf(String componentId) {
        if (directionOf(componentId) == NONE) return -1;
        try {
            return Long.parseLong(componentId.substring(componentId.lastIndexOf(':') + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
