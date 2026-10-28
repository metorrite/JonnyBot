package com.runeclans.bot.roletools;

import com.runeclans.bot.roletools.RoleRules.Mode;
import com.runeclans.bot.roletools.RoleRules.Status;
import com.runeclans.bot.roletools.RoleToolkitRepository.Job;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The role toolkit SQL against the developer's own local database. Skipped when there isn't one; uses a made-up server id and removes what it adds. */
class RoleToolkitRepositoryDatabaseTest {
    private static final long GUILD = 990_000_000_000_000_041L;

    private ConnectionSupplier connections;
    private RoleToolkitRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = LocalDatabase.supplier();
        repository = new RoleToolkitRepository(connections);
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connections != null) clean();
    }

    private void clean() throws Exception {
        for (String table : new String[]{"role_bulk_job", "role_toolkit_allow"}) {
            try (Connection c = connections.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ?")) {
                s.setLong(1, GUILD);
                s.executeUpdate();
            }
        }
    }

    @Test
    void theAllowListIsPerGroupAndReplacedAsAWhole() {
        repository.setAllowed(GUILD, "mods", Set.of(1L, 2L));
        repository.setAllowed(GUILD, "helpers", Set.of(3L));
        repository.setAllowed(GUILD, "mods", Set.of(2L, 4L));

        assertEquals(Set.of(2L, 4L), repository.allowed(GUILD).get("mods"));
        assertEquals(Set.of(3L), repository.allowed(GUILD).get("helpers"));
        repository.setAllowed(GUILD, "mods", Set.of());
        assertFalse(repository.allowed(GUILD).containsKey("mods"));
    }

    @Test
    void aDryRunIsStoredAsPlannedAndOnlyExplicitMovesChangeIt() {
        Job job = repository.plan(GUILD, Mode.ADD, 10, 20, 7, List.of(100L, 101L, 102L));

        assertEquals(Status.PLANNED, job.status());
        assertEquals(3, job.total());
        assertEquals(List.of(100L, 101L), repository.members(job.id(), 2));
        assertFalse(repository.move(job.id(), Status.PAUSED, Status.RUNNING), "wrong starting point: nothing moves");
        assertTrue(repository.move(job.id(), Status.PLANNED, Status.RUNNING));
        assertFalse(repository.move(job.id(), Status.PLANNED, Status.RUNNING), "a second press of the button loses");
        assertTrue(repository.hasRunning(GUILD));
    }

    @Test
    void finishingMembersKeepsTheCountersInStepAndOnlyOnce() {
        Job job = repository.plan(GUILD, Mode.REMOVE, 10, 20, 7, List.of(100L, 101L));

        repository.finishMember(job.id(), 100, true);
        repository.finishMember(job.id(), 100, true);
        repository.finishMember(job.id(), 101, false);

        Job after = repository.get(GUILD, job.id()).orElseThrow();
        assertEquals(1, after.done());
        assertEquals(1, after.failed());
        assertTrue(repository.pending(job.id(), 10).isEmpty());
    }

    @Test
    void aRestartPausesAJobThatWasRunningAndNeverResumesIt() {
        Job job = repository.plan(GUILD, Mode.ADD, 10, 20, 7, List.of(100L));
        repository.move(job.id(), Status.PLANNED, Status.RUNNING);

        new RoleToolkitRepository(connections); // what the bot does on its next start

        assertEquals(Status.PAUSED, repository.get(GUILD, job.id()).orElseThrow().status());
        assertFalse(repository.hasRunning(GUILD));
    }
}
