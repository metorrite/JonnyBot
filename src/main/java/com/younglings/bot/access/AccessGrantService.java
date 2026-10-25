package com.younglings.bot.access;

import com.younglings.bot.discord.DiscordLookups;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The one check behind every restricted area of the website: may this Discord user enter this area because of a grant on the owner's list?
 * A user grant matches that user. A server grant matches someone who is in that server (JonnyBot has to be in it to look) with at least the
 * grant's level there. Expired grants never match. The list comes from {@link GateSource} (the hub with RUNECLANS_HUB_ACCESS, else the bot). Anything that cannot be looked up counts as no. A grant only lifts the gate on its area; it
 * grants nothing in any server. Answers are remembered for a minute, because the website asks on every page.
 */
@BService
public class AccessGrantService {
    private static final long REMEMBER_MS = 60_000;

    private record Key(AccessArea area, long userId) {}
    private record Answer(boolean allowed, long expiresAt) {}

    private final GateSource gates;
    private final Map<Key, Answer> remembered = new ConcurrentHashMap<>();
    /** Replaceable so tests can move time without waiting. */
    LongSupplier clock = System::currentTimeMillis;

    public AccessGrantService(GateSource gates) {
        this.gates = gates;
    }

    public boolean allows(JDA jda, AccessArea area, long userId) {
        if (userId == 0) return false;
        long now = clock.getAsLong();
        Answer known = remembered.get(new Key(area, userId));
        if (known != null && known.expiresAt() > now) return known.allowed();

        boolean allowed;
        try {
            allowed = matches(gates.grantsFor(userId), jda, area, userId, Instant.ofEpochMilli(now));
        } catch (DiscordLookups.Slow e) {
            throw e; // Discord didn't say whether they're in a granted server: the asker gets "try again", not a wrong no
        } catch (RuntimeException e) {
            return false; // the list could not be read: closed, and not remembered
        }
        remembered.put(new Key(area, userId), new Answer(allowed, now + REMEMBER_MS));
        return allowed;
    }

    /** The decision itself, without the remembering. */
    static boolean matches(List<AccessGrant> grants, JDA jda, AccessArea area, long userId, Instant now) {
        for (AccessGrant grant : grants) {
            if (grant.area() != area || grant.expired(now)) continue;
            if (grant.isUser()) {
                if (grant.userId() == userId) return true;
                continue;
            }
            Guild guild = jda.getGuildById(grant.guildId());
            if (guild == null) continue;
            if (grant.level().satisfiedBy(memberIn(guild, userId))) return true;
        }
        return false;
    }

    /** Forget what was remembered: the list just changed. */
    public void listChanged() {
        remembered.clear();
    }

    /** Null when they're not in that server; {@link DiscordLookups.Slow} when Discord didn't answer in time (never remembered). */
    private static Member memberIn(Guild guild, long userId) {
        return DiscordLookups.memberOrSlow(guild, userId);
    }
}
