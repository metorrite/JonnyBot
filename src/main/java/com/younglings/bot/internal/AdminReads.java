package com.younglings.bot.internal;

import com.younglings.bot.cachebus.CacheBus;
import net.dv8tion.jda.api.entities.Guild;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Keeps slow dashboard pages from tying up the website's requests (see {@link StaleCache}). Answers are kept per server and path, so
 * two servers never see each other's, and any change made through the admin API drops that server's answers.
 */
final class AdminReads {
    /** An answer this young is served as is. */
    static final long FRESH_MS = 30_000;
    /** An answer this old is still served at once while a new one is fetched. */
    static final long MAX_STALE_MS = 10 * 60_000;
    /** The longest a request waits for work that has to be done now. */
    static final long BUDGET_MS = 2_000;

    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();
    private static final StaleCache CACHE = new StaleCache(System::currentTimeMillis, workers());

    private AdminReads() {}

    private static ThreadPoolExecutor workers() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), runnable -> {
            Thread thread = new Thread(runnable, "admin-reads-" + THREAD_NUMBER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static String prefix(Guild guild) {
        return guild.getId() + "|";
    }

    /** The answer for {@code key} (a path with its query) in this server, from the cache when it can be. */
    static <T> T cached(Guild guild, String key, Supplier<T> loader) {
        return CACHE.get(prefix(guild) + key, FRESH_MS, MAX_STALE_MS, BUDGET_MS, loader);
    }

    /** The same for an answer that isn't tied to a server's dashboard (the site's news): {@code key} must not start with a server id. */
    static <T> T cachedFor(String key, long freshMs, long maxStaleMs, Supplier<T> loader) {
        return CACHE.get(key, freshMs, maxStaleMs, BUDGET_MS, loader);
    }

    /** Drops every answer whose key starts with {@code prefix}. */
    static void forgetPrefix(String prefix) {
        CACHE.invalidate(prefix);
    }

    /**
     * Runs a change that may wait on Discord. Past the time limit the caller is told it is still going (it will probably finish, so they should
     * look before trying again; {@code onLate} records that), and the server's cached pages are dropped when it ends either way.
     */
    static <T> T bounded(Guild guild, Supplier<T> task, Runnable onLate) {
        try {
            return CACHE.within(BUDGET_MS, task::get, () -> forget(guild));
        } catch (StaleCache.Late late) {
            onLate.run();
            throw new TicketAdminApi.ApiError(503, "Discord is slow right now, so this is still being done. Check in a minute before trying it again.");
        }
    }

    /** Runs {@code task} with the time limit and nothing else; throws {@link StaleCache.Late} if it isn't done in time (it carries on). */
    static <T, E extends Exception> T withinBudget(StaleCache.Work<T, E> task) throws E {
        return CACHE.within(BUDGET_MS, task, () -> {});
    }

    /** Drops this server's cached pages; called after every change. */
    static void forget(Guild guild) {
        CACHE.invalidate(prefix(guild));
        CacheBus.changed(AdminReadsCache.CACHE_AREA, guild.getIdLong());
    }

    /** Drops one server's cached pages because another process changed something there ({@link AdminReadsCache}); sends no signal. */
    static void forgetSignalled(long guildId) {
        CACHE.invalidate(guildId + "|");
    }

    /** For tests, and when the cache listener (re)connects ({@link AdminReadsCache}). */
    static void clearAll() {
        CACHE.clear();
    }
}
