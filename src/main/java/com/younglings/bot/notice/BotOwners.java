package com.younglings.bot.notice;

import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Who owns JonnyBot itself, as opposed to who runs one server it is in: the Discord application's owner (or the members of
 * its team), plus anyone listed in {@code OWNER_IDS}. Owners may post the notices every dashboard shows. Looked up from
 * Discord rather than configured, so it works without extra setup, and remembered briefly because it almost never changes.
 */
@BService
public class BotOwners {
    private static final Logger log = LoggerFactory.getLogger(BotOwners.class);
    private static final long REFRESH_MS = 10 * 60_000L;

    private final BotConfig config;
    private volatile Set<Long> owners = Set.of();
    private volatile long fetchedAt;

    public BotOwners(BotConfig config) {
        this.config = config;
    }

    public boolean isOwner(JDA jda, long userId) {
        return owners(jda).contains(userId);
    }

    private Set<Long> owners(JDA jda) {
        if (System.currentTimeMillis() - fetchedAt < REFRESH_MS) return owners;
        Set<Long> found = new HashSet<>(config.getOwnerIds());
        try {
            var info = jda.retrieveApplicationInfo().complete();
            if (info.getOwner() != null) found.add(info.getOwner().getIdLong());
            if (info.getTeam() != null) info.getTeam().getMembers().forEach(member -> found.add(member.getUser().getIdLong()));
        } catch (Exception e) {
            log.warn("Couldn't ask Discord who owns the bot; using the configured owners only.", e);
        }
        owners = Set.copyOf(found);
        fetchedAt = System.currentTimeMillis();
        return owners;
    }
}
