package com.younglings.bot.clansite;

import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Builder v2 layouts (T-301) against the developer's local Postgres (skipped where there isn't one), with a made-up server id. */
class ClanSiteLayoutDatabaseTest {
    private static final long GUILD = 987654321030001L;
    private static final long ACTOR = 987654321030002L;
    private static final long OTHER_GUILD = 987654321030003L;
    private static final long OTHER_ACTOR = 987654321030004L;
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
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.clan_site_layout WHERE guild_id IN (?, ?)")) {
            s.setLong(1, GUILD);
            s.setLong(2, OTHER_GUILD);
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

    @Test
    void publishFromAnOlderRevisionIsRefused() {
        layouts.saveDraft(GUILD, "{\"v\":1,\"x\":1}", 0, ACTOR);
        assertEquals(2, layouts.saveDraft(GUILD, "{\"v\":1,\"x\":2}", 1, OTHER_ACTOR));
        assertEquals(-1, layouts.publishDraft(GUILD, ACTOR, null, 1), "the editor loaded revision 1");
        assertNull(layouts.byGuild(GUILD).orElseThrow().published(), "nothing went public");
        assertTrue(layouts.versions(GUILD, 30).isEmpty(), "no version was recorded");
        assertEquals(2, layouts.publishDraft(GUILD, ACTOR, null, 2));
        assertEquals(0, layouts.publishDraft(OTHER_GUILD, ACTOR, null, 1), "no draft at all");
    }

    /** Tab A's save holds the row while tab B's publish (made from the older revision) waits on it: once A commits, B is refused. */
    @Test
    void publishInterleavedWithASaveIsRefused() throws Exception {
        layouts.saveDraft(GUILD, "{\"v\":1,\"x\":1}", 0, ACTOR);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection tabA = db.getConnection()) {
            tabA.setAutoCommit(false);
            try (PreparedStatement s = tabA.prepareStatement(
                    "UPDATE younglings.clan_site_layout SET draft = ?, draft_rev = draft_rev + 1 WHERE guild_id = ? AND draft_rev = 1")) {
                s.setString(1, "{\"v\":1,\"x\":2}");
                s.setLong(2, GUILD);
                assertEquals(1, s.executeUpdate());
            }
            Future<Integer> tabB = pool.submit(() -> layouts.publishDraft(GUILD, OTHER_ACTOR, null, 1));
            Thread.sleep(300);
            assertFalse(tabB.isDone(), "the publish waits on the save's row lock");
            tabA.commit();
            assertEquals(-1, tabB.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        var saved = layouts.byGuild(GUILD).orElseThrow();
        assertNull(saved.published(), "nothing went public");
        assertEquals(2, saved.draftRev());
    }

    // ---------- versions (T-363) ----------

    @Test
    void publishingRecordsAVersionWithItsLabel() {
        layouts.saveDraft(GUILD, "{\"v\":2,\"x\":1}", 0, ACTOR);
        assertTrue(layouts.publishDraft(GUILD, ACTOR, "  First go  "));
        layouts.saveDraft(GUILD, "{\"v\":2,\"x\":2}", 1, ACTOR);
        assertTrue(layouts.publishDraft(GUILD, ACTOR, "x".repeat(ClanSiteLayoutRepository.MAX_VERSION_LABEL + 10)));

        List<ClanSiteLayoutRepository.Version> versions = layouts.versions(GUILD, 30);
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(0).rev(), "newest first");
        assertEquals(ClanSiteLayoutRepository.MAX_VERSION_LABEL, versions.get(0).label().length(), "labels are cut");
        assertEquals("First go", versions.get(1).label());
        assertEquals(ACTOR, versions.get(1).publishedBy());
        assertNotNull(versions.get(1).publishedAt());
        assertNull(versions.get(1).layout(), "the list leaves the layouts out");

        var one = layouts.version(GUILD, versions.get(1).id(), 30).orElseThrow();
        assertTrue(one.layout().contains("\"x\":1"));
        assertTrue(layouts.version(OTHER_GUILD, versions.get(1).id(), 30).isEmpty(), "another server can't read it");
    }

    @Test
    void theThirtyFirstPublishDropsTheOldest() {
        int rev = layouts.saveDraft(GUILD, "{\"v\":2,\"n\":0}", 0, ACTOR);
        for (int i = 1; i <= ClanSiteLayoutRepository.KEPT_VERSIONS + 1; i++) {
            assertTrue(layouts.publishDraft(GUILD, ACTOR, "v" + i));
            rev = layouts.saveDraft(GUILD, "{\"v\":2,\"n\":" + i + "}", rev, ACTOR);
        }
        List<ClanSiteLayoutRepository.Version> versions = layouts.versions(GUILD, 100);
        assertEquals(ClanSiteLayoutRepository.KEPT_VERSIONS, versions.size());
        assertEquals("v31", versions.get(0).label());
        assertEquals("v2", versions.get(versions.size() - 1).label(), "v1 was dropped");
        assertEquals(ClanSiteLayoutRepository.KEPT_VERSIONS, count(), "nothing else is left in the table");

        List<ClanSiteLayoutRepository.Version> free = layouts.versions(GUILD, 3);
        assertEquals(List.of("v31", "v30", "v29"), free.stream().map(ClanSiteLayoutRepository.Version::label).toList());
        long older = versions.get(5).id();
        assertTrue(layouts.version(GUILD, older, 3).isEmpty(), "a plan that shows 3 can't open the 6th newest");
        assertTrue(layouts.version(GUILD, older, 30).isPresent(), "but it is still kept");
    }

    @Test
    void restoringCopiesIntoTheDraftAndNeverPublishes() {
        layouts.saveDraft(GUILD, "{\"v\":2,\"x\":1}", 0, ACTOR);
        layouts.publishDraft(GUILD, ACTOR, "one");
        layouts.saveDraft(GUILD, "{\"v\":2,\"x\":2}", 1, ACTOR);
        layouts.publishDraft(GUILD, ACTOR, "two");
        layouts.saveDraft(GUILD, "{\"v\":2,\"x\":3}", 2, ACTOR);
        long first = layouts.versions(GUILD, 30).get(1).id();

        assertEquals(-1, layouts.restoreVersion(GUILD, first, 2, ACTOR), "made from an older revision");
        assertEquals(4, layouts.restoreVersion(GUILD, first, 3, OTHER_ACTOR), "a new draft revision");
        var after = layouts.byGuild(GUILD).orElseThrow();
        assertTrue(after.draft().contains("\"x\":1"), "the draft is the old version");
        assertTrue(after.published().contains("\"x\":2"), "the public site didn't change");
        assertEquals(2, after.publishedRev());
        assertEquals(OTHER_ACTOR, after.updatedBy());
        assertEquals(5, layouts.restoreVersion(GUILD, first, 0, ACTOR), "without a base revision it always applies");

        assertEquals(-1, layouts.restoreVersion(OTHER_GUILD, first, 0, ACTOR), "another server's version can't be restored");
        assertEquals(-1, layouts.restoreVersion(GUILD, Long.MAX_VALUE, 0, ACTOR), "a version that doesn't exist");
    }

    @Test
    void deletingTheSiteDeletesItsVersions() {
        layouts.saveDraft(GUILD, "{\"v\":2}", 0, ACTOR);
        layouts.publishDraft(GUILD, ACTOR, null);
        assertEquals(1, count());
        layouts.delete(GUILD);
        assertEquals(0, count());
    }

    private int count() {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("SELECT count(*) FROM younglings.clan_site_layout_version WHERE guild_id = ?")) {
            s.setLong(1, GUILD);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
