package com.younglings.bot.internal;

import com.younglings.bot.internal.CockpitRules.Member;
import com.younglings.bot.runescape.ClanMemberRepository;
import com.younglings.bot.runescape.RuneScapeDatabaseInitializer;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Clan health page's tables and queries over a real database (skipped without one), and the roster sync's XP-change stamp (T-593). */
class SiteCockpitStoreDatabaseTest {
    private static final long G = Long.parseLong("9".repeat(17) + "3");
    private static final long OFFICER = Long.parseLong("6".repeat(18));
    private static ConnectionSupplier supplier;
    private static SiteCockpitStore store;
    private static ClanMemberRepository roster;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
        RuneScapeDatabaseInitializer schema = new RuneScapeDatabaseInitializer(supplier);
        store = new SiteCockpitStore(supplier, schema);
        roster = new ClanMemberRepository(supplier, schema);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : List.of("clan_member", "clan_roster_event", "player_link", "site_cockpit_settings", "site_cockpit_mark", "rsn_rename_candidate")) {
            sql("DELETE FROM younglings." + table + " WHERE guild_id = " + G);
        }
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            s.executeUpdate();
        }
    }

    private static Member only(String rsn) {
        return store.members(G, store.links(G)).stream().filter(m -> m.rsn().equals(rsn)).findFirst().orElseThrow();
    }

    @Test
    void theRosterSyncStampsWhenTheXpTotalChanged() throws Exception {
        roster.upsert(G, "Zezima", "Recruit", 1_000, 0);
        Member first = only("Zezima");
        assertNotNull(first.xpWatchedSince(), "watched from the first sync");
        assertNull(first.xpChangedAt(), "no change seen yet");

        roster.upsert(G, "Zezima", "Recruit", 1_000, 0);
        assertNull(only("Zezima").xpChangedAt(), "the same total is no change");

        roster.upsert(G, "zezima", "Corporal", 1_500, 0);
        Member changed = only("Zezima");
        assertNotNull(changed.xpChangedAt(), "a new total is a change");
        assertEquals("Corporal", changed.rank());

        // a row from before the columns existed: watching starts at its next sync, and a change shows at once
        sql("UPDATE younglings.clan_member SET xp_watched_since = NULL, xp_changed_at = NULL WHERE guild_id = " + G);
        roster.upsert(G, "Zezima", "Corporal", 1_500, 0);
        assertNotNull(only("Zezima").xpWatchedSince());
        assertNull(only("Zezima").xpChangedAt());
    }

    @Test
    void linksJoinsLeavesAndRenamesAreReadByServer() throws Exception {
        roster.upsert(G, "Linked", "Recruit", 1, 0);
        roster.upsert(G, "Fresh", "Recruit", 1, 0);
        sql("INSERT INTO younglings.player_link (guild_id, discord_user_id, rsn, verification_method) VALUES (" + G + ", " + OFFICER + ", 'Linked', 'ADMIN_MANUAL')");
        sql("INSERT INTO younglings.clan_roster_event (guild_id, rsn, event_type) VALUES (" + G + ", 'Fresh', 'JOIN'), (" + G + ", 'Gone', 'LEAVE')");
        sql("INSERT INTO younglings.rsn_rename_candidate (guild_id, old_rsn, new_rsn, confidence, basis, status) VALUES (" + G
                + ", 'Gone', 'Fresh', 'MEDIUM', 'xp', 'PENDING'), (" + G + ", 'X', 'Y', 'HIGH', 'xp', 'REJECTED')");

        Map<String, Long> links = store.links(G);
        assertEquals(OFFICER, links.get("linked"));
        assertEquals(OFFICER, only("Linked").discordId());
        assertNull(only("Fresh").discordId());
        OffsetDateTime weekAgo = OffsetDateTime.now(ZoneOffset.UTC).minusDays(7);
        assertEquals(2, store.rosterEvents(G, weekAgo).size());
        assertEquals(1, store.renames(G, weekAgo).size(), "a rejected rename is left out");
        assertTrue(store.knownName(G, "linked"));
        assertFalse(store.knownName(G, "Nobody"));
    }

    @Test
    void settingsAndMarks() {
        assertNull(store.quietDays(G), "never saved: the page uses the default");
        store.saveQuietDays(G, 21, OFFICER);
        assertEquals(21, store.quietDays(G));

        store.mark(G, "quiet", "zezima", OFFICER, "Officer");
        store.mark(G, "quiet", "zezima", OFFICER, "Officer Two");
        assertEquals(1, store.marks(G).size(), "marking again moves the one mark");
        assertEquals("Officer Two", store.marks(G).getFirst().byName());
        store.unmark(G, "quiet", "zezima");
        assertTrue(store.marks(G).isEmpty());

        store.mark(G, "left", "gone", OFFICER, null);
        store.forget(G);
        assertTrue(store.marks(G).isEmpty());
        assertNull(store.quietDays(G));
    }
}
