package com.runeclans.bot.database;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.config.BotRole;
import com.runeclans.bot.util.BotScheduler;
import com.runeclans.bot.util.LaneOwners;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * Picks the one api replica that runs the api-owned lanes ({@link LaneOwners.Owner#API}, T-702). Only with {@code BOT_ROLE=api}: in
 * {@code all} there is one process and in {@code gateway} no api lane runs, so nothing here starts.
 *
 * <p>Each api replica keeps a connection of its own and asks for one Postgres session advisory lock every {@link #CHECK_SECONDS}
 * seconds. The replica holding it leads. Postgres drops the lock the moment that session ends (the replica stops, crashes or loses its
 * connection), and the next replica to ask takes over. A leader that finds its connection broken stops leading at once, before
 * reconnecting. Worst case two replicas both run api work for up to one check (a leader whose connection broke, before it notices), which
 * those lanes already tolerate: their writes are idempotent or guarded in Postgres.
 *
 * <p>The lock query gives up after {@link #QUERY_TIMEOUT_SECONDS} seconds, so a follower on a half-open connection drops it and reconnects
 * instead of waiting forever. A leader cut off from the database by the network can't release the lock itself: Postgres frees it only
 * when it notices the session is dead, which with default TCP keepalives can take about two hours, and no api replica leads meanwhile.
 * Before running more than one api replica, set the database's {@code tcp_keepalives_idle} / {@code tcp_keepalives_interval} (or
 * {@code idle_session_timeout}) to a minute or two.
 */
@BService
public class ApiLeaderElection {
    private static final Logger log = LoggerFactory.getLogger(ApiLeaderElection.class);
    static final int CHECK_SECONDS = 15;
    /** The advisory lock's key: any fixed number no other lock in the bot uses ("RCAPI" in ASCII). */
    static final long LOCK_KEY = 0x5243415049L;
    static final int QUERY_TIMEOUT_SECONDS = 5;

    public ApiLeaderElection(BotConfig config) {
        if (BotRole.current() != BotRole.API) return;
        String databaseUrl = config.getDatabaseUrl();
        Election election = new Election(() -> OwnConnection.open(databaseUrl, "bot-api-leader"));
        BotScheduler.lane("api-leader").scheduleWithFixedDelay(() -> {
            try {
                LaneOwners.leading(election.check());
            } catch (Throwable t) {
                LaneOwners.leading(false);
                log.warn("api leader check failed; not leading until the next check", t);
            }
        }, 0, CHECK_SECONDS, TimeUnit.SECONDS);
    }

    /** Opens the election's own connection. */
    public interface Opener {
        Connection open() throws SQLException;
    }

    /** One replica's side of the election, kept apart from the service so the tests can run two. */
    static final class Election {
        private final Opener opener;
        private Connection connection;
        private boolean leading;

        Election(Opener opener) {
            this.opener = opener;
        }

        /** Whether this replica leads after this check. Never throws for a database problem: it just doesn't lead. */
        synchronized boolean check() {
            try {
                if (connection != null && !connection.isValid(5)) drop();
                if (connection == null) connection = opener.open();
                if (!leading) {
                    try (PreparedStatement s = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                        s.setLong(1, LOCK_KEY);
                        s.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                        try (ResultSet rs = s.executeQuery()) {
                            leading = rs.next() && rs.getBoolean(1);
                        }
                    }
                    if (leading) log.info("This api process now runs the api lanes");
                }
                return leading;
            } catch (SQLException e) {
                drop();
                log.warn("api leader check: {}", e.getMessage());
                return false;
            }
        }

        /** Stops leading and closes the connection (which frees the lock). */
        synchronized void drop() {
            if (leading) log.info("This api process no longer runs the api lanes");
            leading = false;
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // closing a broken connection; the lock went with its session anyway
                }
            }
            connection = null;
        }
    }
}
