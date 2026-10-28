package com.runeclans.bot.cachebus;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.util.BotScheduler;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Cross-process cache invalidation (docs/SCALING-STATE.md M10, T-701). The settings caches keep a server's rows in memory with no TTL; once
 * the bot runs as more than one process, a change saved on one (the website's {@code api} process) must reach the others (the gateway).
 * <ul>
 *   <li>A cache that changed here calls {@link #changed}{@code (area, guildId)} after its write has committed; that queues
 *   {@code pg_notify('bot_cache', 'area:guildId')} on a lane of its own, so the request that wrote never waits for it.</li>
 *   <li>One connection of its own, outside the pool, {@code LISTEN}s for those signals ({@link CacheListener}) and hands them to every
 *   {@link CacheInvalidator} of the area, which drops that server's entries.</li>
 * </ul>
 * The writer hears its own signal too and drops the entry it just wrote; the next read loads the same row again. That costs one read per
 * change and keeps the rule simple: whatever the order of two processes' writes, every process ends with what Postgres holds.
 * <p>
 * Off unless {@code BOT_CACHE_NOTIFY=true}: with one process there is nothing to tell, and then nothing here runs (no connection, no
 * {@code NOTIFY}); local writes update the caches exactly as before either way.
 */
@BService
public class CacheBus {
    private static final Logger log = LoggerFactory.getLogger(CacheBus.class);
    static final String SWITCH_VARIABLE = "BOT_CACHE_NOTIFY";

    /** The running bus's sender, or null when it's off (then {@link #changed} does nothing). */
    private static volatile Sender sender;

    public CacheBus(BotConfig config, ConnectionSupplier connections, List<CacheInvalidator> invalidators) {
        if (!switchedOn(setting())) return;
        CacheDispatcher dispatcher = new CacheDispatcher(invalidators);
        String databaseUrl = config.getDatabaseUrl();
        new CacheListener(() -> open(databaseUrl), dispatcher, BotScheduler.lane("cache-bus-listen")).start();
        sender = new Sender(connections, BotScheduler.lane("cache-bus-send"));
        log.info("Cache signals on: listening on {} for {}", CacheSignal.CHANNEL, dispatcher.areas());
    }

    /**
     * Tells every process (this one too) that {@code area}'s cache of {@code guildId} changed. Call it after the write has committed. Never
     * throws and never waits; does nothing while the bus is off.
     */
    public static void changed(String area, long guildId) {
        Sender current = sender;
        if (current == null || guildId <= 0 || !CacheSignal.validArea(area)) return;
        current.queue(new CacheSignal(area, guildId).payload());
    }

    /** Puts {@code next} in place of the running sender and returns the old one (tests: null switches sending off again). */
    static Sender swapSender(Sender next) {
        Sender old = sender;
        sender = next;
        return old;
    }

    /** Sends queued signals one at a time on its own lane, on a pool connection. */
    static final class Sender {
        private final ConnectionSupplier connections;
        private final ScheduledExecutorService lane;
        /** Signals waiting on the lane: the same one queued twice is sent once. */
        private final Set<String> queued = ConcurrentHashMap.newKeySet();

        Sender(ConnectionSupplier connections, ScheduledExecutorService lane) {
            this.connections = connections;
            this.lane = lane;
        }

        void queue(String payload) {
            if (!queued.add(payload)) return;
            try {
                lane.execute(() -> {
                    queued.remove(payload);
                    send(payload);
                });
            } catch (RejectedExecutionException e) {
                queued.remove(payload);
            }
        }

        private void send(String payload) {
            try (Connection c = connections.getConnection()) {
                CacheBus.notify(c, payload);
            } catch (SQLException | RuntimeException e) {
                // the other processes keep the old entry until their next reconnect clears everything; nothing better to do here
                log.warn("Couldn't send cache signal {}: {}", payload, e.getMessage());
            }
        }
    }

    /** Sends one signal on {@code c} (committed at once when the connection auto-commits). */
    static void notify(Connection c, String payload) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT pg_notify(?, ?)")) {
            s.setString(1, CacheSignal.CHANNEL);
            s.setString(2, payload);
            s.execute();
        }
    }

    static boolean switchedOn(String raw) {
        return raw != null && (raw.strip().equalsIgnoreCase("true") || raw.strip().equals("1"));
    }

    private static String setting() {
        String raw = System.getenv(SWITCH_VARIABLE);
        if (raw == null || raw.isBlank()) raw = Dotenv.configure().ignoreIfMissing().load().get(SWITCH_VARIABLE);
        return raw;
    }

    /** The listener's own connection, from {@code DATABASE_URL} ({@code postgresql://user:password@host:port/db}). */
    private static Connection open(String databaseUrl) throws SQLException {
        URI uri = URI.create(databaseUrl.trim());
        String userInfo = uri.getUserInfo() == null ? "" : uri.getUserInfo();
        int colon = userInfo.indexOf(':');
        Properties props = new Properties();
        props.setProperty("user", colon < 0 ? userInfo : userInfo.substring(0, colon));
        if (colon >= 0) props.setProperty("password", userInfo.substring(colon + 1));
        props.setProperty("ApplicationName", "bot-cache-listener");
        props.setProperty("connectTimeout", "10");
        props.setProperty("tcpKeepAlive", "true");
        String jdbc = "jdbc:postgresql://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getPath();
        return DriverManager.getConnection(jdbc, props);
    }
}
