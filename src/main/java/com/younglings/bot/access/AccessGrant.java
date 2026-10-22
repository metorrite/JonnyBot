package com.younglings.bot.access;

import java.time.Instant;

/**
 * One entry on the restricted-content access list: a Discord user, or a server plus the least a person must be there, may enter an area.
 * Exactly one of {@code userId} and {@code guildId} is set. It only lifts the gate on that area; it never gives rights in any server.
 */
public record AccessGrant(long id, AccessArea area, Long userId, Long guildId, AccessLevel level, String note, long grantedBy, Instant grantedAt, Instant expiresAt) {

    public boolean isUser() {
        return userId != null;
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
