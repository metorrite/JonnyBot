package com.runeclans.bot.clansite;

import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Clan website role settings (T-368) against the local Postgres (skipped where there isn't one), with made-up ids. */
class SiteRoleDatabaseTest {
    private static final long BASE = Long.parseLong("9".repeat(17));
    private static final long GUILD = BASE + 31;
    private static final long FIRST = BASE + 32;
    private static final long SECOND = BASE + 33;
    private ConnectionSupplier db;
    private SiteRoleRepository roles;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        roles = new SiteRoleRepository(db);
        roles.delete(GUILD);
    }

    @AfterEach
    void tearDown() {
        if (roles != null) roles.delete(GUILD);
    }

    @Test
    void aServerWithoutSettingsHasTheDefaults() {
        assertEquals(SiteRoles.Settings.DEFAULT, roles.get(GUILD));
        assertFalse(roles.get(GUILD).editorsMayPublish(), "editors can't publish until an admin says so");
    }

    @Test
    void settingsAreSavedAndTheClaimIsKept() {
        assertTrue(roles.claimIfUnclaimed(GUILD, FIRST));
        roles.save(GUILD, new SiteRoles.Settings(List.of(BASE + 1, BASE + 2), List.of(BASE + 3), List.of(), true, null), SECOND);
        SiteRoles.Settings s = roles.get(GUILD);
        assertEquals(List.of(BASE + 1, BASE + 2), s.editorRoleIds());
        assertEquals(List.of(BASE + 3), s.memberRoleIds());
        assertEquals(List.of(), s.staffRoleIds());
        assertTrue(s.editorsMayPublish());
        assertEquals(FIRST, s.claimedBy(), "saving the role lists never changes who claimed the site");
    }

    @Test
    void onlyTheFirstClaimCounts() {
        roles.save(GUILD, SiteRoles.Settings.DEFAULT, FIRST);
        assertTrue(roles.claimIfUnclaimed(GUILD, FIRST), "a row without a claim can be claimed");
        assertFalse(roles.claimIfUnclaimed(GUILD, SECOND));
        assertEquals(FIRST, roles.get(GUILD).claimedBy());

        roles.setClaimedBy(GUILD, SECOND, FIRST);
        assertEquals(SECOND, roles.get(GUILD).claimedBy(), "handed over");
        roles.setClaimedBy(GUILD, null, SECOND);
        assertNull(roles.get(GUILD).claimedBy(), "back to the server owner alone");

        roles.delete(GUILD);
        assertEquals(SiteRoles.Settings.DEFAULT, roles.get(GUILD));
    }
}
