package com.younglings.bot.clansite;

import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Builder v2 layouts (T-301) against the developer's local Postgres (skipped where there isn't one), with a made-up server id. */
class ClanSiteLayoutDatabaseTest {
    private static final long GUILD = 987654321030001L;
    private static final long ACTOR = 987654321030002L;
    private ConnectionSupplier db;
    private ClanSiteLayoutRepository layouts;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        layouts = new ClanSiteLayoutRepository(db, new ClanSiteRepository(db));
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.clan_site_layout WHERE guild_id = ?")) {
            s.setLong(1, GUILD);
            s.executeUpdate();
        }
    }

    @Test
    void draftsAreRevisedAndStaleSavesRefused() {
        assertTrue(layouts.byGuild(GUILD).isEmpty());
        assertEquals(1, layouts.saveDraft(GUILD, "{\"v\":1}", 0, ACTOR));
        assertEquals(-1, layouts.saveDraft(GUILD, "{\"v\":1,\"x\":1}", 0, ACTOR), "a second 'first save' is stale");
        assertEquals(2, layouts.saveDraft(GUILD, "{\"v\":1,\"x\":2}", 1, ACTOR));
        assertEquals(-1, layouts.saveDraft(GUILD, "{\"v\":1,\"x\":3}", 1, ACTOR), "made from an older revision");

        var saved = layouts.byGuild(GUILD).orElseThrow();
        assertEquals(2, saved.draftRev());
        assertTrue(saved.draft().contains("\"x\":2"));
        assertNull(saved.published(), "nothing is public before a publish");
        assertEquals(ACTOR, saved.updatedBy());
    }

    @Test
    void publishingCopiesTheDraftAndLaterDraftsStayPrivate() {
        assertFalse(layouts.publishDraft(GUILD, ACTOR), "no draft yet");
        layouts.saveDraft(GUILD, "{\"v\":1,\"x\":1}", 0, ACTOR);
        assertTrue(layouts.publishDraft(GUILD, ACTOR));
        layouts.saveDraft(GUILD, "{\"v\":1,\"x\":2}", 1, ACTOR);

        var saved = layouts.byGuild(GUILD).orElseThrow();
        assertTrue(saved.published().contains("\"x\":1"), "the public copy is the one published");
        assertEquals(1, saved.publishedRev());
        assertEquals(2, saved.draftRev());

        layouts.delete(GUILD);
        assertTrue(layouts.byGuild(GUILD).isEmpty());
    }
}
