package com.runeclans.bot.appeal;

import com.runeclans.bot.appeal.AppealRules.Settings;
import com.runeclans.bot.appeal.AppealRules.Status;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Appeals over real SQL (T-033): settings default off, one open appeal per case, a decision only once, and taking one back. Skipped without a local database. */
class AppealRepositoryDatabaseTest {
    private static final long GUILD = 999_000_000_000_000_091L;
    private static ConnectionSupplier supplier;
    private AppealRepository repository;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new AppealRepository(supplier);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection()) {
            for (String sql : new String[]{"DELETE FROM younglings.appeal WHERE guild_id = ?", "DELETE FROM younglings.appeal_setting WHERE guild_id = ?"}) {
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setLong(1, GUILD);
                    s.executeUpdate();
                }
            }
        }
    }

    @Test
    void settingsStartOffAndAreKept() {
        assertFalse(repository.settings(GUILD).enabled());
        repository.saveSettings(new Settings(GUILD, true, 55L, 3, null));
        Settings saved = repository.settings(GUILD);
        assertTrue(saved.enabled());
        assertEquals(55L, saved.panelId());
        assertEquals(3, saved.cooldownDays());
    }

    @Test
    void aCaseHasOneOpenAppealAtATimeAndIsDecidedOnce() {
        var first = repository.create(GUILD, 5, 7, "someone", "first try").orElseThrow();
        assertTrue(repository.create(GUILD, 5, 7, "someone", "second try").isEmpty(), "a second open appeal for the same case can't be written");
        assertTrue(repository.decide(GUILD, first.id(), Status.REJECTED, 9, "no"));
        assertFalse(repository.decide(GUILD, first.id(), Status.APPROVED, 9, "yes"), "already decided");
        assertEquals("no", repository.find(GUILD, first.id()).orElseThrow().note());
        assertTrue(repository.create(GUILD, 5, 7, "someone", "after the cooldown").isPresent(), "once decided, a new one can be written (the cooldown is the service's rule)");
        assertEquals(2, repository.forCase(GUILD, 5, 7).size());
        assertEquals(Status.OPEN, repository.forGuild(GUILD, 10).get(0).status(), "open ones come first");
    }

    @Test
    void anApprovalDiscordRefusedIsOpenAgain() {
        var a = repository.create(GUILD, 6, 7, "someone", "please").orElseThrow();
        assertTrue(repository.decide(GUILD, a.id(), Status.APPROVED, 9, ""));
        repository.reopen(GUILD, a.id());
        var back = repository.find(GUILD, a.id()).orElseThrow();
        assertEquals(Status.OPEN, back.status());
        assertEquals(null, back.decidedBy());
    }
}
