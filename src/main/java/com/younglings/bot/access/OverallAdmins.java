package com.younglings.bot.access;

import com.younglings.bot.notice.BotOwners;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Who is an overall RuneClans admin (N5, runeclans.com/admin): the built-in admins, who are the bot's owners as {@link BotOwners} decides (the
 * Discord application's owner or team, plus {@code OWNER_IDS}; no Discord id is written in the code), and the people the owner added by Discord
 * user id ({@link OverallAdminRepository}). Overall admins use the whole admin panel and may open and change every clan site, its admin
 * panel and its permissions; only a built-in admin may add or remove overall admins, and a built-in admin can't be removed.
 * <p>
 * The added list is remembered for {@link #CACHE_MILLIS} and dropped at once by every change made here, so a removal takes effect on the next
 * request (the site viewer answers cached by {@code SiteRoleGuard} follow within a minute). A failed read keeps the last known list, so a
 * database blip never makes every overall admin a stranger, and never adds anyone.
 */
@BService
public class OverallAdmins {
    private static final Logger log = LoggerFactory.getLogger(OverallAdmins.class);
    static final long CACHE_MILLIS = 30_000;

    private final OverallAdminRepository repository;
    private final BotOwners owners;
    /** Replaceable so tests can move time. */
    LongSupplier clock = System::currentTimeMillis;
    private volatile Set<Long> added = Set.of();
    private volatile long loadedUntil;

    public OverallAdmins(OverallAdminRepository repository, BotOwners owners) {
        this.repository = repository;
        this.owners = owners;
    }

    /** Whether {@code userId} is an overall admin: built in, or added. */
    public boolean isOverallAdmin(JDA jda, long userId) {
        if (userId <= 0) return false;
        return isBuiltIn(jda, userId) || added().contains(userId);
    }

    /** Whether {@code userId} is a built-in overall admin (one of the bot's owners), who alone may change the list. */
    public boolean isBuiltIn(JDA jda, long userId) {
        return userId > 0 && owners.isOwner(jda, userId);
    }

    /** The added admins, newest last, read fresh. */
    public List<OverallAdminRepository.Entry> list() {
        List<OverallAdminRepository.Entry> all = repository.all();
        remember(all);
        return all;
    }

    public void put(long userId, String note, long actorId) {
        repository.put(userId, note, actorId);
        loadedUntil = 0;
        log.info("Overall admins: {} added {}", actorId, userId);
    }

    public boolean remove(long userId, long actorId) {
        boolean removed = repository.delete(userId);
        loadedUntil = 0;
        if (removed) log.info("Overall admins: {} removed {}", actorId, userId);
        return removed;
    }

    private Set<Long> added() {
        if (clock.getAsLong() < loadedUntil) return added;
        try {
            remember(repository.all());
        } catch (RuntimeException e) {
            log.warn("Couldn't read the overall admins ({}); keeping the list already known.", e.getMessage());
            loadedUntil = clock.getAsLong() + 5_000;
        }
        return added;
    }

    private void remember(List<OverallAdminRepository.Entry> all) {
        added = all.stream().map(OverallAdminRepository.Entry::userId).collect(Collectors.toUnmodifiableSet());
        loadedUntil = clock.getAsLong() + CACHE_MILLIS;
    }
}
