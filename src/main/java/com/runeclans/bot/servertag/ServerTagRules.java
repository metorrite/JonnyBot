package com.runeclans.bot.servertag;

import java.util.Collection;

/** Whether a member's server tag is one to act on, with nothing from Discord in it. */
public final class ServerTagRules {
    private ServerTagRules() {}

    /**
     * @param primaryGuildId  the server whose tag the member shows, or null if they show none
     * @param identityEnabled whether the member has chosen to show that tag (a hidden tag is stale and is never acted on)
     */
    public static boolean shouldAct(ServerTagConfig config, Long primaryGuildId, boolean identityEnabled, boolean isBot, boolean isOwner, boolean staff,
                                    Collection<Long> memberRoleIds) {
        if (!config.enabled() || primaryGuildId == null || !identityEnabled) return false;
        if (isBot || isOwner || staff) return false;
        if (!config.flaggedGuildIds().contains(primaryGuildId)) return false;
        for (long exempt : config.exemptRoleIds()) if (memberRoleIds.contains(exempt)) return false;
        return true;
    }

    /** The reason written on the case and in the DM. The tag text is a member's own choice, so it is kept out of the sentence's structure and shortened. */
    public static String reason(String tag, long flaggedGuildId) {
        String shown = tag == null ? "" : tag.strip();
        if (shown.length() > 8) shown = shown.substring(0, 8);
        return "Server tag moderation: shows the tag" + (shown.isEmpty() ? "" : " " + shown) + " of a flagged server (" + flaggedGuildId + ")";
    }
}
