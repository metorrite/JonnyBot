package com.runeclans.bot.autorole;

import java.util.List;

/**
 * A server's autoroles: roles handed to everyone who joins, with a separate list for bots, after an optional delay.
 * A delay is how long to wait before giving them, so a role that would let people in (see rules, pass a gate) is
 * only given once the new member has had a moment, which also gives a screening bot a head start.
 * <p>
 * {@code memberRemoveRoleIds} and {@code botRemoveRoleIds} are the opposite action: roles taken away from someone who joins (for instance one a
 * returning member had kept), on the same delay. Both are empty unless a server adds some.
 */
public record AutoroleConfig(long guildId, boolean enabled, int delaySeconds, List<Long> memberRoleIds, List<Long> botRoleIds,
                             List<Long> memberRemoveRoleIds, List<Long> botRemoveRoleIds) {
    public static final int MAX_ROLES = 10;
    public static final int MAX_DELAY_SECONDS = 7 * 24 * 3600;

    /** Without roles to take away: how every server's autoroles were before that existed. */
    public AutoroleConfig(long guildId, boolean enabled, int delaySeconds, List<Long> memberRoleIds, List<Long> botRoleIds) {
        this(guildId, enabled, delaySeconds, memberRoleIds, botRoleIds, List.of(), List.of());
    }

    public static AutoroleConfig defaults(long guildId) {
        return new AutoroleConfig(guildId, false, 0, List.of(), List.of());
    }

    /** The roles to take away from a person or a bot who just joined. */
    public List<Long> removalsFor(boolean bot) {
        return bot ? botRemoveRoleIds : memberRemoveRoleIds;
    }

    /** Whether this config does anything at all for a person or a bot who joins. */
    public boolean anythingFor(boolean bot) {
        return !rolesFor(bot).isEmpty() || !removalsFor(bot).isEmpty();
    }

    /** The roles to hand out to a person or a bot who just joined. */
    public List<Long> rolesFor(boolean bot) {
        return bot ? botRoleIds : memberRoleIds;
    }
}
