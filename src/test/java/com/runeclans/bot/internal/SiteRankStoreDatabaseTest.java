package com.runeclans.bot.internal;

import com.runeclans.bot.internal.RankAdvisorRules.Due;
import com.runeclans.bot.internal.RankAdvisorRules.Hold;
import com.runeclans.bot.internal.RankAdvisorRules.Promoted;
import com.runeclans.bot.internal.RankAdvisorRules.Rule;
import com.runeclans.bot.internal.RankAdvisorRules.Tick;
import com.runeclans.bot.internal.RankAdvisorRules.Turn;
import com.runeclans.bot.runescape.ClanMemberRepository;
import com.runeclans.bot.runescape.RuneScapeDatabaseInitializer;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rank-up advisor's tables over a real database (skipped without one), and the roster sync's rank stamp (T-594). */
class SiteRankStoreDatabaseTest {
    private static final long G = Long.parseLong("9".repeat(17) + "4");
    private static final long OWNER = Long.parseLong("5".repeat(18));
    private static ConnectionSupplier supplier;
    private static SiteRankStore store;
    private static ClanMemberRepository roster;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
        RuneScapeDatabaseInitializer schema = new RuneScapeDatabaseInitializer(supplier);
        store = new SiteRankStore(supplier, schema);
        roster = new ClanMemberRepository(supplier, schema);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : List.of("clan_member", "clan_member_points", "site_rank_rule", "site_rank_settings", "site_rank_tick", "site_rank_hold",
                "site_rank_due", "site_rank_week", "site_rank_promoted")) {
            sql("DELETE FROM younglings." + table + " WHERE guild_id = " + G);
        }
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            s.executeUpdate();
        }
    }

    private static SiteRankStore.Row only(String rsn) {
        return store.roster(G).stream().filter(m -> m.rsn().equalsIgnoreCase(rsn)).findFirst().orElseThrow();
    }

    @Test
    void xpGainedCountsFromWhenTheSyncSawTheCurrentRank() throws Exception {
        roster.upsert(G, "Zezima", "Recruit", 1_000, 0);
        SiteRankStore.Row first = only("Zezima");
        assertEquals(0L, first.xpGained(), "a new member starts at nothing gained");
        assertNotNull(first.rankSince());

        roster.upsert(G, "Zezima", "Recruit", 4_000, 0);
        assertEquals(3_000L, only("Zezima").xpGained(), "the same rank keeps counting");

        roster.upsert(G, "Zezima", "Corporal", 5_000, 0);
        assertEquals(0L, only("Zezima").xpGained(), "a new rank starts again");

        sql("UPDATE younglings.clan_member SET rank_since = NULL, rank_xp = NULL WHERE guild_id = " + G);
        assertNull(only("Zezima").xpGained(), "a member from before the column has no starting point");
        roster.upsert(G, "Zezima", "Corporal", 6_000, 0);
        assertEquals(0L, only("Zezima").xpGained(), "the next sync is the starting point");
    }

    @Test
    void pointsAndTheStaffJoinDateAreRead() throws Exception {
        roster.upsert(G, "Zezima", "Recruit", 1_000, 0);
        sql("INSERT INTO younglings.clan_member_points (guild_id, rsn, total_points) VALUES (" + G + ", 'zezima', 42)");
        sql("UPDATE younglings.clan_member SET clan_joined_at = DATE '2020-01-02' WHERE guild_id = " + G);
        SiteRankStore.Row row = only("Zezima");
        assertEquals(42, row.points());
        assertEquals(2020, row.joined().getYear());
    }

    @Test
    void rulesAreReplacedAsAWholeAndReadInLadderOrder() {
        store.saveRules(G, List.of(new Rule("Sergeant", null, 30, null, null), new Rule("Corporal", 1_000L, null, 2, 5L),
                new Rule("Captain", null, null, null, null)), OWNER);
        Map<String, Rule> rules = store.rules(G);
        assertEquals(List.of("Corporal", "Sergeant"), List.copyOf(rules.keySet()), "an empty rule isn't kept");
        assertEquals(new Rule("Corporal", 1_000L, null, 2, 5L), rules.get("Corporal"));

        store.saveRules(G, List.of(new Rule("General", null, 90, null, null)), OWNER);
        assertEquals(List.of("General"), List.copyOf(store.rules(G).keySet()));

        store.saveRules(G, List.of(new Rule("Captain", null, null, null, null, "Master CAs")), OWNER);
        assertEquals(new Rule("Captain", null, null, null, null, "Master CAs"), store.rules(G).get("Captain"), "a staff check alone is kept");
        assertTrue(store.serversWithRules().contains(G));
    }

    @Test
    void holdsMoveBetweenPendingAndIgnoredAndIgnoringDropsTheDueEntry() {
        LocalDate week = LocalDate.of(2026, 10, 28);
        store.noteDue(G, List.of(new Due("zezima", "Zezima", "Recruit", "Corporal", week)));
        store.hold(G, "zezima", "Zezima", RankAdvisorRules.PENDING, OWNER, "Owner name");
        assertEquals(List.of("pending"), store.holds(G).stream().map(Hold::kind).toList());
        assertEquals(1, store.due(G).size(), "pending review keeps them due");
        store.hold(G, "zezima", "Zezima", RankAdvisorRules.IGNORED, OWNER, null);
        assertEquals(List.of("ignored"), store.holds(G).stream().map(Hold::kind).toList(), "one hold per member");
        assertTrue(store.due(G).isEmpty(), "an ignored alt is never congratulated");
        store.hold(G, "zezima", "Zezima", null, OWNER, null);
        assertTrue(store.holds(G).isEmpty());
    }

    @Test
    void theWeekTurnsOnceAndIsPostedOnce() {
        LocalDate week = LocalDate.of(2026, 10, 28);
        store.noteDue(G, List.of(new Due("zezima", "Zezima", "Recruit", "Corporal", week.minusWeeks(1))));
        store.noteDue(G, List.of(new Due("zezima", "Zezima", "Recruit", "Corporal", week)));
        assertEquals(week.minusWeeks(1), store.due(G).getFirst().since(), "someone already due keeps their first week");
        assertNull(store.latestWeek(G));
        assertFalse(store.turned(G, week));
        Turn turn = new Turn(List.of(new Promoted("zezima", "Zezima", "Recruit", "Corporal", "Zez")), store.due(G));
        assertTrue(store.turn(G, week, turn));
        assertFalse(store.turn(G, week, turn), "a second turn of the same week writes nothing");
        assertTrue(store.turned(G, week));
        assertTrue(store.due(G).isEmpty());
        SiteRankStore.Week w = store.latestWeek(G);
        assertEquals(week, w.start());
        assertEquals(List.of("Zezima"), w.promoted().stream().map(Promoted::rsn).toList());
        assertEquals("Zez", w.promoted().getFirst().discordName());
        assertNull(w.postedAt());
        assertTrue(store.claimPost(G, week));
        assertFalse(store.claimPost(G, week), "never posted twice");
        store.saveJob(G, week, 42L);
        assertEquals(42L, store.latestWeek(G).jobId());
        store.forget(G);
        assertNull(store.latestWeek(G));
    }

    @Test
    void theCongratsPostIsOffUntilSaved() {
        assertFalse(store.congrats(G).on());
        assertNull(store.congrats(G).channelId());
        long channel = Long.parseLong("3".repeat(18));
        store.saveCongrats(G, new SiteRankStore.Congrats(true, channel, "Well done {name}"), OWNER);
        assertTrue(store.congrats(G).on());
        assertEquals(channel, store.congrats(G).channelId());
        assertEquals("Well done {name}", store.congrats(G).text());
    }

    @Test
    void ticksAreKeptPerRankAndCanBeTakenBack() {
        assertTrue(store.tick(G, "zezima", "Corporal", OWNER, "Owner name"), "a first tick is new");
        assertFalse(store.tick(G, "zezima", "Corporal", OWNER, "Owner name"), "a repeated tick isn't, so no second congrats post");
        assertTrue(store.tick(G, "zezima", "Sergeant", OWNER, null));
        List<Tick> ticks = store.ticks(G);
        assertEquals(2, ticks.size());
        store.untick(G, "zezima", "Corporal");
        assertEquals(List.of("Sergeant"), store.ticks(G).stream().map(Tick::rank).toList());
        store.forget(G);
        assertTrue(store.ticks(G).isEmpty());
        assertTrue(store.rules(G).isEmpty());
    }
}
