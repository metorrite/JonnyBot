package com.runeclans.bot.honeypot;

import java.util.Collection;

/** The honeypot's decision, with nothing from Discord in it: whether someone who touched the decoy should be acted on. */
public final class HoneypotRules {
    private HoneypotRules() {}

    public enum Trigger { POSTED_IN_CHANNEL, TOOK_ROLE }

    /**
     * @param channelId   where the message was (for POSTED_IN_CHANNEL), ignored for TOOK_ROLE
     * @param roleId      the role that was added (for TOOK_ROLE), ignored for POSTED_IN_CHANNEL
     * @param memberRoleIds every role the person holds
     * @param staff       the person is an Admin-tier member or has the Administrator permission
     */
    public static boolean shouldAct(HoneypotConfig config, Trigger trigger, long channelId, long roleId, boolean isBot, boolean isOwner, boolean staff,
                                    Collection<Long> memberRoleIds) {
        if (!config.enabled()) return false;
        if (isBot || isOwner || staff) return false;
        for (long exempt : config.exemptRoleIds()) if (memberRoleIds.contains(exempt)) return false;
        return switch (trigger) {
            case POSTED_IN_CHANNEL -> config.channelId() != null && config.channelId() == channelId;
            case TOOK_ROLE -> config.roleId() != null && config.roleId() == roleId;
        };
    }

    /** The reason written on the case and in the DM. */
    public static String reason(Trigger trigger) {
        return switch (trigger) {
            case POSTED_IN_CHANNEL -> "Honeypot: posted in a decoy channel that no member should use";
            case TOOK_ROLE -> "Honeypot: took a decoy role that no member should have";
        };
    }
}
