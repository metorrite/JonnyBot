package com.younglings.bot.moderation;

import net.dv8tion.jda.api.Permission;

/** What a moderation case records. The first six are done by commands here; WARN is the existing /warn. */
public enum ModAction {
    BAN("Ban", "banned", Permission.BAN_MEMBERS),
    UNBAN("Unban", "unbanned", Permission.BAN_MEMBERS),
    KICK("Kick", "kicked", Permission.KICK_MEMBERS),
    TIMEOUT("Timeout", "timed out", Permission.MODERATE_MEMBERS),
    UNTIMEOUT("Timeout removed", "had their timeout removed", Permission.MODERATE_MEMBERS),
    SOFTBAN("Softban", "softbanned", Permission.BAN_MEMBERS),
    WARN("Warning", "warned", null);

    public final String label;
    /** "banned": used in "You have been banned from ...". */
    public final String past;
    /** The Discord permission JonnyBot needs to do it, or null when it does nothing on Discord. */
    public final Permission permission;

    ModAction(String label, String past, Permission permission) {
        this.label = label;
        this.past = past;
        this.permission = permission;
    }

    /** Whether the member is told by DM before the action (they could not be reached after a ban or kick). */
    public boolean dmsBefore() {
        return this == BAN || this == KICK || this == SOFTBAN || this == TIMEOUT;
    }
}
