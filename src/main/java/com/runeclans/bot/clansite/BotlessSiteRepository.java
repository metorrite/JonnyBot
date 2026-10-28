package com.runeclans.bot.clansite;

import com.runeclans.bot.cachebus.CacheBus;
import com.runeclans.bot.cachebus.CacheInvalidator;
import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Discord servers whose clan website is run without the bot in them (a bot-less site): one row per server, written the first time a person
 * on the site builder beta list manages it from the website with signed sign-in facts ({@code ActorFacts}). Everything else about the site is
 * stored by server id as usual (site, layout, roles, profile, pictures, clan name in the server's settings); this row only says "this server's
 * site is meant to work without the bot", which is what lets it into the builder beta, keeps the leave grace from starting on its plan, and
 * puts its clan in the daily clan sync.
 * <p>
 * The list is small and read on every site request, so it is remembered in memory and refreshed on each write.
 */
@BService
public class BotlessSiteRepository implements CacheInvalidator {
    /** The {@code bot_cache} area of this class's caches (docs/SCALING-STATE.md M10). */
    static final String CACHE_AREA = "botless-sites";

    private static final Logger log = LoggerFactory.getLogger(BotlessSiteRepository.class);

    private final ConnectionSupplier connectionSupplier;
    private volatile Set<Long> known;
    /** Bumped before every drop of {@link #known}, so a read that started before a change can't put the old list back after it. */
    private final AtomicLong generation = new AtomicLong();

    public BotlessSiteRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "bot-less clan websites", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.botless_site (
                    guild_id BIGINT PRIMARY KEY,
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """
        ));
    }

    /** Whether this server's site is a bot-less one. */
    public boolean contains(long guildId) {
        return ids().contains(guildId);
    }

    /** Every bot-less site's server. */
    public Set<Long> ids() {
        Set<Long> current = known;
        if (current != null) return current;
        long started = generation.get();
        Set<Long> loaded = ConcurrentHashMap.newKeySet();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT guild_id FROM younglings.botless_site");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) loaded.add(rs.getLong("guild_id"));
        } catch (SQLException e) {
            log.error("Failed to read the bot-less clan websites", e);
            throw new RuntimeException("Failed to read the bot-less clan websites", e);
        }
        Set<Long> frozen = Set.copyOf(loaded);
        known = frozen;
        // a drop since this read started may have come before the line above: then don't keep what may be the old list
        if (generation.get() != started) known = null;
        return frozen;
    }

    /** Records the server as bot-less (first time only). True when this call added it. */
    public boolean register(long guildId, long userId) {
        if (contains(guildId)) return false;
        String sql = "INSERT INTO younglings.botless_site (guild_id, created_by) VALUES (?, ?) ON CONFLICT (guild_id) DO NOTHING";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            boolean added = s.executeUpdate() == 1;
            dropKnown();
            CacheBus.changed(CACHE_AREA, guildId);
            if (added) log.info("Clan website of guild {} is now bot-less (set up by {})", guildId, userId);
            return added;
        } catch (SQLException e) {
            log.error("Failed to record the bot-less clan website of guild {}", guildId, e);
            throw new RuntimeException("Failed to record the bot-less clan website", e);
        }
    }

    /** Forgets the server (its website was deleted for good). */
    public void remove(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.botless_site WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            s.executeUpdate();
            dropKnown();
            CacheBus.changed(CACHE_AREA, guildId);
        } catch (SQLException e) {
            log.error("Failed to forget the bot-less clan website of guild {}", guildId, e);
            throw new RuntimeException("Failed to forget the bot-less clan website", e);
        }
    }

    /** The ids as a sorted list, for logs and the owner's console. */
    public List<Long> sorted() {
        List<Long> out = new ArrayList<>(ids());
        out.sort(Long::compare);
        return out;
    }

    // ---------- cross-process invalidation (CacheBus) ----------

    @Override
    public String cacheArea() {
        return CACHE_AREA;
    }

    @Override
    public void evictCached(long guildId) {
        dropKnown();
    }

    @Override
    public void evictAllCached() {
        dropKnown();
    }

    private void dropKnown() {
        generation.incrementAndGet();
        known = null;
    }
}
