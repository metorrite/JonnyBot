package com.younglings.bot.autorole;

import java.util.List;

/**
 * A server's autoroles: roles handed to everyone who joins, with a separate list for bots, after an optional delay.
 * A delay is how long to wait before giving them, so a role that would let people in (see rules, pass a gate) is
 * only given once the new member has had a moment, which also gives a screening bot a head start.
 */
public record AutoroleConfig(long guildId, boolean enabled, int delaySeconds, List<Long> memberRoleIds, List<Long> botRoleIds) {
    public static final int MAX_ROLES = 10;
    public static final int MAX_DELAY_SECONDS = 7 * 24 * 3600;

    public static AutoroleConfig defaults(long guildId) {
        return new AutoroleConfig(guildId, false, 0, List.of(), List.of());
    }

    /** The roles to hand out to a person or a bot who just joined. */
    public List<Long> rolesFor(boolean bot) {
        return bot ? botRoleIds : memberRoleIds;
    }
}
