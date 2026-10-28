package com.runeclans.bot.cachebus;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Holds one Postgres connection of its own (never one of the pool's, so it can't starve web requests) that {@code LISTEN}s on
 * {@link CacheSignal#CHANNEL}, and hands each signal to the {@link CacheDispatcher}. It runs on its own lane: each turn waits up to
 * {@link #WAIT_MS} for signals and then queues the next turn, so the lane's worker is free between turns.
 * <p>
 * When the connection fails it is closed and opened again after a growing pause (1 s doubling to a minute). Every time it (re)connects, every
 * cache is cleared, since signals sent while it was away are lost.
 */
public final class CacheListener {
    private static final Logger log = LoggerFactory.getLogger(CacheListener.class);

    /** How long one turn waits for signals. */
    static final int WAIT_MS = 1_000;
    /** How often an idle connection is checked (a dropped connection may not say so on its own). */
    static final long CHECK_EVERY_MS = 30_000;
    static final long MAX_BACKOFF_MS = 60_000;

    /** Opens the listener's own connection. */
    @FunctionalInterface
    public interface Opener {
        Connection open() throws SQLException;
    }

    private final Opener opener;
    private final CacheDispatcher dispatcher;
    private final ScheduledExecutorService lane;

    /** Only touched on the lane. */
    private Connection connection;
    private int failures;
    private long lastCheck;
    private volatile boolean stopped;
    private volatile boolean listening;

    public CacheListener(Opener opener, CacheDispatcher dispatcher, ScheduledExecutorService lane) {
        this.opener = opener;
        this.dispatcher = dispatcher;
        this.lane = lane;
    }

    public void start() {
        lane.execute(this::turn);
    }

    /** Stops listening and closes the connection (tests; the bot keeps it for its whole life). */
    public void stop() {
        stopped = true;
        try {
            lane.submit(this::close).get(WAIT_MS * 3L, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // the lane is busy or gone: the connection closes with the process
        }
    }

    /** Whether the connection is open and listening right now. */
    public boolean listening() {
        return listening;
    }

    static long backoffMs(int failures) {
        if (failures <= 0) return 0;
        return Math.min(MAX_BACKOFF_MS, 1_000L << Math.min(failures - 1, 16));
    }

    private void turn() {
        if (stopped) {
            close();
            return;
        }
        long next = 0;
        try {
            if (connection == null) connect();
            PGNotification[] signals = connection.unwrap(PGConnection.class).getNotifications(WAIT_MS);
            if (signals != null) {
                for (PGNotification signal : signals) {
                    if (CacheSignal.CHANNEL.equals(signal.getName())) dispatcher.dispatch(signal.getParameter());
                }
            }
            long now = System.currentTimeMillis();
            if (now - lastCheck >= CHECK_EVERY_MS) {
                lastCheck = now;
                if (!connection.isValid(5)) throw new SQLException("the cache listener's connection is no longer valid");
            }
        } catch (SQLException | RuntimeException e) {
            close();
            if (stopped) return;
            failures++;
            next = backoffMs(failures);
            if (failures == 1 || failures % 10 == 0) {
                log.warn("Cache listener lost its connection ({} time(s) in a row); retrying in {} ms: {}", failures, next, e.getMessage());
            }
        }
        if (stopped) {
            close();
            return;
        }
        try {
            lane.schedule(this::turn, next, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            close();
        }
    }

    private void connect() throws SQLException {
        Connection opened = opener.open();
        try {
            opened.setAutoCommit(true);
            try (Statement s = opened.createStatement()) {
                s.execute("LISTEN " + CacheSignal.CHANNEL);
            }
        } catch (SQLException | RuntimeException e) {
            try {
                opened.close();
            } catch (SQLException ignored) {
                // already failing
            }
            throw e;
        }
        connection = opened;
        lastCheck = System.currentTimeMillis();
        listening = true;
        if (failures > 0) log.info("Cache listener reconnected after {} failure(s)", failures);
        failures = 0;
        // anything sent while this process wasn't listening is lost, so nothing cached can be trusted
        dispatcher.evictAll();
    }

    private void close() {
        listening = false;
        Connection current = connection;
        connection = null;
        if (current == null) return;
        try {
            current.close();
        } catch (SQLException ignored) {
            // it was failing anyway
        }
    }
}
