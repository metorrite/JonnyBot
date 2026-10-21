package com.younglings.bot.moderation;

import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the case SQL against the developer's local Postgres (skipped where there isn't one), with made-up server ids. */
class ModCaseRepositoryDatabaseTest {
    private static final long A = 987654321012501L, B = 987654321012502L;
    private ConnectionSupplier db;
    private ModCaseRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        repository = new ModCaseRepository(db);
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        for (String table : List.of("mod_case", "mod_case_counter", "mod_setting")) {
            try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id IN (?, ?)")) {
                s.setLong(1, A);
                s.setLong(2, B);
                s.executeUpdate();
            }
        }
    }

    private ModCase add(long guild, long user, ModAction action) {
        return repository.record(guild, user, "user" + user, 5L, "Mod", action, "because", action == ModAction.TIMEOUT ? 30 : null, ModCase.COMMAND);
    }

    @Test
    void caseNumbersCountPerServerFromOne() {
        assertEquals(1, add(A, 1, ModAction.BAN).caseNo());
        assertEquals(2, add(A, 2, ModAction.KICK).caseNo());
        assertEquals(1, add(B, 1, ModAction.BAN).caseNo(), "another server starts at 1");
        assertEquals(3, add(A, 1, ModAction.WARN).caseNo());
        assertEquals(2, add(B, 9, ModAction.WARN).caseNo());
    }

    @Test
    void numbersAreNeverReusedEvenWhenManyCasesAreWrittenAtOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Long>> jobs = new ArrayList<>();
            for (int i = 0; i < 24; i++) jobs.add(() -> add(A, 1, ModAction.WARN).caseNo());
            TreeSet<Long> numbers = new TreeSet<>();
            for (Future<Long> f : pool.invokeAll(jobs)) numbers.add(f.get());
            assertEquals(24, numbers.size(), "every case has its own number");
            assertEquals(1L, numbers.first());
            assertEquals(24L, numbers.last());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void casesAreFoundByNumberMemberAndModeratorAndOnlyInTheirOwnServer() {
        add(A, 1, ModAction.TIMEOUT);
        add(A, 2, ModAction.BAN);
        repository.record(A, 1, "user1", 77L, "Other Mod", ModAction.KICK, "r", null, ModCase.AUTOPUNISH);
        add(B, 1, ModAction.BAN);

        assertEquals(30, repository.find(A, 1).orElseThrow().durationMinutes());
        assertNull(repository.find(A, 2).orElseThrow().durationMinutes());
        assertTrue(repository.find(A, 99).isEmpty());
        assertEquals(List.of(3L, 1L), repository.forUser(A, 1, 10).stream().map(ModCase::caseNo).toList());
        assertEquals(List.of(3L), repository.byModerator(A, 77L, 10).stream().map(ModCase::caseNo).toList());
        assertEquals(3, repository.recent(A, 10).size());
        assertEquals(1, repository.recent(B, 10).size());
        assertEquals(ModCase.AUTOPUNISH, repository.find(A, 3).orElseThrow().source());
    }

    @Test
    void editingAReasonKeepsTheFirstOneAndWhoChangedIt() {
        add(A, 1, ModAction.BAN);
        assertTrue(repository.editReason(A, 1, "better reason", 42L));
        assertTrue(repository.editReason(A, 1, "best reason", 43L));
        ModCase c = repository.find(A, 1).orElseThrow();
        assertEquals("best reason", c.reason());
        assertEquals("because", c.originalReason(), "the first reason is kept through later edits");
        assertEquals(43L, c.editedBy());
        assertFalse(repository.editReason(A, 99, "x", 1L));
        assertFalse(repository.editReason(B, 1, "x", 1L), "another server's case is not reachable");
    }

    @Test
    void settingsStartAtTheSafeDefaultsAndRoundTrip() {
        ModSettings fresh = repository.settings(A);
        assertTrue(fresh.dmReason());
        assertFalse(fresh.showModerator());
        assertNull(fresh.appealUrl());
        assertTrue(fresh.protectedRoleIds().isEmpty());

        repository.saveSettings(new ModSettings(A, false, true, "https://example.com/appeal", List.of(11L, 22L)));
        ModSettings read = repository.settings(A);
        assertFalse(read.dmReason());
        assertTrue(read.showModerator());
        assertEquals("https://example.com/appeal", read.appealUrl());
        assertEquals(List.of(11L, 22L), read.protectedRoleIds());
        assertTrue(repository.settings(B).dmReason(), "another server is untouched");
    }
}
