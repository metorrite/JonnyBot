package com.younglings.bot.warning;

/** Who may be warned by whom: the checks that stop a warning going to the wrong place, as words for the moderator. */
public final class WarnPolicy {
    public static final int MAX_REASON = 500;

    private WarnPolicy() {}

    /**
     * Why this warning must not be recorded, or null when it can be. {@code actorCanInteract} is Discord's role-hierarchy rule: a
     * moderator cannot warn someone whose highest role is level with or above their own.
     */
    public static String refusal(boolean targetIsActor, boolean targetIsBot, boolean actorCanInteract, String reason) {
        if (targetIsActor) return "You can't warn yourself.";
        if (targetIsBot) return "Bots can't be warned.";
        if (!actorCanInteract) return "You can't warn someone whose highest role is level with or above yours.";
        if (reason == null || reason.strip().isEmpty()) return "Give a reason for the warning.";
        if (reason.strip().length() > MAX_REASON) return "Keep the reason to " + MAX_REASON + " characters or fewer.";
        return null;
    }

    /** "3 warnings" / "1 warning". */
    public static String count(int n) {
        return n + (n == 1 ? " warning" : " warnings");
    }
}
