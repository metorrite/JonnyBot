package com.younglings.bot.moderation;

import java.util.List;

/**
 * A server's moderation settings.
 *
 * @param dmReason         tell the member by DM what happened and why (on by default)
 * @param showModerator    put the moderator's name in that DM (off by default: members are told the reason, not who)
 * @param appealUrl        where a punished member can appeal, shown in the DM; null for none
 * @param protectedRoleIds members holding any of these roles can never be moderated
 */
public record ModSettings(long guildId, boolean dmReason, boolean showModerator, String appealUrl, List<Long> protectedRoleIds) {
    public static final int MAX_PROTECTED_ROLES = 25;

    public static ModSettings defaults(long guildId) {
        return new ModSettings(guildId, true, false, null, List.of());
    }
}
