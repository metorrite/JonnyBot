package com.younglings.bot.notice;

import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Who owns JonnyBot itself, as opposed to who runs one server it is in: the Discord application's owner (or the members of
 * its team), plus anyone listed in {@code OWNER_IDS}. Owners may post the notices every dashboard shows. Looked up from
 * Discord rather than configured, so it works without extra setup, and remembered briefly because it almost never changes.
 * <p>
 * A failed lookup (Discord unreachable, or the bot just starting or stopping) is not remembered as "nobody owns it": whoever was
 * known before stays known, and the lookup is tried again shortly instead of after the full refresh time.
 */
@BService
public class BotOwners {
    private static final Logger log = LoggerFactory.getLogger(BotOwners.class);
    private static final long REFRESH_MS = 10 * 60_000L;
    private static final long RETRY_MS = 30_000L;

    private final BotConfig config;
    /** Replaceable so tests can move time without waiting. */
    LongSupplier clock = System::currentTimeMillis;
    private volatile Set<Long> owners = Set.of();
    private volatile long nextLookupAt;

    public BotOwners(BotConfig config) {
        this.config = config;
    }

    public boolean isOwner(JDA jda, long userId) {
        return owners(jda).contains(userId) || config.getOwnerIds().contains(userId);
    }

    private Set<Long> owners(JDA jda) {
        if (clock.getAsLong() < nextLookupAt) return owners;

        try {
            Set<Long> found = new HashSet<>(config.getOwnerIds());
            var info = jda.retrieveApplicationInfo().complete();
            if (info.getOwner() != null) found.add(info.getOwner().getIdLong());
            if (info.getTeam() != null) info.getTeam().getMembers().forEach(member -> found.add(member.getUser().getIdLong()));
            owners = Set.copyOf(found);
            nextLookupAt = clock.getAsLong() + REFRESH_MS;
        } catch (Exception e) {
            // Keep who we knew, and ask again soon. This is routine while the bot is starting or stopping.
            log.warn("Couldn't ask Discord who owns the bot ({}); keeping the owners already known and retrying shortly.", e.getMessage());
            nextLookupAt = clock.getAsLong() + RETRY_MS;
        }
        return owners;
    }
}
