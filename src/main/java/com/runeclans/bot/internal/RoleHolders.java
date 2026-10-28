package com.runeclans.bot.internal;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * How many of the people the bot sees in a server now hold each role (T-626), so the website can warn before a role that most of the server
 * holds is made a site admin or editor role. The bot caches only members who are online, so it is a share of those, not a full count.
 */
record RoleHolders(int seen, Map<Long, Integer> held) {
    RoleHolders {
        held = Map.copyOf(held);
    }

    /** The people (not bots) in the bot's member cache, and the roles each holds. */
    static RoleHolders of(Guild guild) {
        Map<Long, Integer> held = new HashMap<>();
        int seen = 0;
        for (Member m : guild.getMemberCache()) {
            if (m.getUser().isBot()) continue;
            seen++;
            for (Role r : m.getRoles()) held.merge(r.getIdLong(), 1, Integer::sum);
        }
        return new RoleHolders(seen, held);
    }

    /** The same from each person's role ids, for tests. */
    static RoleHolders count(Collection<? extends Collection<Long>> people) {
        Map<Long, Integer> held = new HashMap<>();
        for (Collection<Long> roles : people) for (long id : roles) held.merge(id, 1, Integer::sum);
        return new RoleHolders(people.size(), held);
    }

    int holders(long roleId) {
        return held.getOrDefault(roleId, 0);
    }
}
