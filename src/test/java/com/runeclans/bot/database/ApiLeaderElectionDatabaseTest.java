package com.runeclans.bot.database;

import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-702: exactly one api replica leads, and another takes over the moment the leader's session ends. */
class ApiLeaderElectionDatabaseTest {
    @Test
    void oneLeaderAtATimeAndAQuickTakeOver() throws Exception {
        ConnectionSupplier pool = LocalDatabase.supplier();
        ApiLeaderElection.Election first = new ApiLeaderElection.Election(pool::getConnection);
        ApiLeaderElection.Election second = new ApiLeaderElection.Election(pool::getConnection);
        try {
            assertTrue(first.check(), "the first to ask leads");
            assertFalse(second.check(), "the lock is taken");
            assertTrue(first.check(), "the leader keeps it");
            assertFalse(second.check());

            first.drop(); // the leader stops: its session ends and the lock goes with it
            assertTrue(second.check(), "the next replica to ask takes over");
            assertFalse(first.check(), "the old leader is a follower now");
        } finally {
            first.drop();
            second.drop();
        }
    }

    @Test
    void aBrokenConnectionMeansNotLeading() {
        ApiLeaderElection.Election broken = new ApiLeaderElection.Election(() -> {
            throw new SQLException("database down");
        });
        assertFalse(broken.check());
        assertFalse(broken.check());
    }

    @Test
    void aLeaderWhoseConnectionDiedStopsLeadingAtTheNextCheck() throws Exception {
        ConnectionSupplier pool = LocalDatabase.supplier();
        Connection[] opened = new Connection[1];
        ApiLeaderElection.Election leader = new ApiLeaderElection.Election(() -> opened[0] = pool.getConnection());
        ApiLeaderElection.Election other = new ApiLeaderElection.Election(pool::getConnection);
        try {
            assertTrue(leader.check());
            opened[0].close(); // the session is gone, and with it the lock
            assertTrue(other.check(), "another replica can take it straight away");
            assertFalse(leader.check(), "the old leader notices and asks again, as a follower");
        } finally {
            leader.drop();
            other.drop();
        }
    }
}
