package com.younglings.bot.events;

import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRepository.Participant;
import com.younglings.bot.events.EventRepository.Progress;
import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Source;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Bingo and scavenger XP tiles (T-543): "Gain 1M Fletching XP" read from the hub's XP ledger, completing when the gain reaches the goal. */
class XpTilesTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long ALICE = Long.parseLong("1".repeat(18));
    private static final long BOB = Long.parseLong("2".repeat(18));
    private static final Instant STARTED = Instant.parse("2026-10-20T18:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-20T19:00:30Z");
    private static final int FLETCHING = 9;
    private static final int MINING = 14;

    private final EventRepository repository = Mockito.mock(EventRepository.class);
    private final EventService service = Mockito.mock(EventService.class);
    private final RuneClansHubClient hub = Mockito.mock(RuneClansHubClient.class);
    private final PluginXpTracker tracker = new PluginXpTracker(repository, service, hub).withClock(Clock.fixed(NOW, ZoneOffset.UTC)).withTiles(true);

    @AfterEach
    void offAgain() {
        PluginObjectiveMatcher.offerXpTiles(false);
    }

    private static ObjectiveSpec tile(String key, String label, long target, int repeats) {
        return new ObjectiveSpec(key, label, "", 1, Evidence.EITHER, target, repeats, List.of());
    }

    private static Event event(long id, String template) {
        return event(id, template, STARTED, NOW.plusSeconds(86_400));
    }

    private static Event event(long id, String template, Instant startsAt, Instant endsAt) {
        return new Event(id, GUILD, template, "Bingo", "", Status.RUNNING, null, ScoringMode.INDIVIDUAL, startsAt, endsAt, 0, "{}", 1,
                STARTED.minusSeconds(7200), null, false);
    }

    private static Participant player(long userId) {
        return new Participant(1, userId, GUILD, null, null, false, null, STARTED.minusSeconds(60), null, null, null, null, null, null);
    }

    /** The hub's gains answer for one player (all skills): {@code skillAndXp} as skill id, XP pairs; the total is their sum. */
    private static RuneClansHubClient.Answer gains(String alias, long... skillAndXp) {
        DataArray bySkill = DataArray.empty();
        long total = 0;
        for (int i = 0; i < skillAndXp.length; i += 2) {
            bySkill.add(DataObject.empty().put("skillId", (int) skillAndXp[i]).put("xp", skillAndXp[i + 1]));
            total += skillAndXp[i + 1];
        }
        return new RuneClansHubClient.Answer(200, DataObject.empty().put("players", DataArray.empty().add(DataObject.empty().put("alias", alias).put("xp", total).put("bySkill", bySkill)))
                .put("notCounted", DataArray.empty()));
    }

    @Test
    void labelsResolveToAnXpGoal() {
        assertEquals(new PluginObjectiveMatcher.XpGoal(FLETCHING, 1_000_000), PluginObjectiveMatcher.xpGoal("Gain 1M Fletching XP"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(MINING, 500_000), PluginObjectiveMatcher.xpGoal("500k XP in Mining"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(MINING, 2_500_000), PluginObjectiveMatcher.xpGoal("gain 2.5m mining experience"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(MINING, 1_000_000), PluginObjectiveMatcher.xpGoal("1,000,000 xp in mining"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(CompetitionTemplate.OVERALL, 10_000_000), PluginObjectiveMatcher.xpGoal("Gain 10M XP"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(CompetitionTemplate.OVERALL, 5_000_000), PluginObjectiveMatcher.xpGoal("5m total xp"));
        assertNull(PluginObjectiveMatcher.xpGoal("99 Fletching"));
        assertNull(PluginObjectiveMatcher.xpGoal("Gain 1M Sailing XP"), "not a skill");
        assertNull(PluginObjectiveMatcher.xpGoal("Nex: Torva boots"));
        assertNull(PluginObjectiveMatcher.xpGoal("Gain 0 Mining XP"));
    }

    @Test
    void shortSkillNamesAndRemarksAreRead() {
        assertEquals(new PluginObjectiveMatcher.XpGoal(8, 1_000_000), PluginObjectiveMatcher.xpGoal("Gain 1M WC XP"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(25, 2_000_000), PluginObjectiveMatcher.xpGoal("2m div xp"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(28, 500_000), PluginObjectiveMatcher.xpGoal("500k XP in Necro"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(24, 1_000_000), PluginObjectiveMatcher.xpGoal("Gain 1M Dung XP"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(15, 1_000_000), PluginObjectiveMatcher.xpGoal("Gain 1M Herb XP"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(18, 1_000_000), PluginObjectiveMatcher.xpGoal("Gain 1M Slay XP"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(27, 1_000_000), PluginObjectiveMatcher.xpGoal("Gain 1M Arch XP"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(22, 1_000_000), PluginObjectiveMatcher.xpGoal("Gain 1M Con XP"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(3, 1_000_000), PluginObjectiveMatcher.xpGoal("Gain 1M Hitpoints XP"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(FLETCHING, 1_000_000), PluginObjectiveMatcher.xpGoal("Gain 1M Fletching XP!"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(FLETCHING, 1_000_000), PluginObjectiveMatcher.xpGoal("Gain 1M Fletching XP (any method)"));
        assertEquals(new PluginObjectiveMatcher.XpGoal(MINING, 500_000), PluginObjectiveMatcher.xpGoal("500k XP in Mining."));
    }

    @Test
    void progressReachesTheTargetWhenTheGainReachesTheGoal() {
        var goal = new PluginObjectiveMatcher.XpGoal(FLETCHING, 1_000_000);
        assertEquals(0, PluginObjectiveMatcher.xpProgress(goal, tile("t", "x", 1, 1), 999_999));
        assertEquals(1, PluginObjectiveMatcher.xpProgress(goal, tile("t", "x", 1, 1), 1_000_000));
        assertEquals(1, PluginObjectiveMatcher.xpProgress(goal, tile("t", "x", 1, 1), 5_000_000), "never more than the tile holds");
        assertEquals(2, PluginObjectiveMatcher.xpProgress(goal, tile("t", "x", 1, 3), 2_400_000), "a repeatable tile counts each million");
        assertEquals(400_000, PluginObjectiveMatcher.xpProgress(goal, tile("t", "x", 1_000_000, 1), 400_000), "a target in XP fills as it comes in");
    }

    @Test
    void theEditorHintSaysWhetherXpTilesAreCounted() {
        PluginObjectiveMatcher.Labels labels = l -> new BingoTiles.Ref(null, l, null);
        var off = PluginObjectiveMatcher.explain(BingoTemplate.KEY, "Gain 1M Fletching XP", labels, i -> false);
        assertFalse(off.auto());
        assertFalse(off.text().contains("switch"), "while off, the hint doesn't mention a setting the organiser can't change: " + off.text());
        PluginObjectiveMatcher.offerXpTiles(true);
        var on = PluginObjectiveMatcher.explain(BingoTemplate.KEY, "Gain 1M Fletching XP", labels, i -> false);
        assertTrue(on.auto());
        assertTrue(on.text().contains("RuneMetrics only"), on.text());
        assertTrue(PluginObjectiveMatcher.explain(ScavengerHuntTemplate.KEY, "500k XP in Mining", labels, i -> false).auto());
    }

    @Test
    void onlyBingoAndScavengerHuntsSayTheyCountXpTiles() {
        PluginObjectiveMatcher.offerXpTiles(true);
        var custom = PluginObjectiveMatcher.explain(CustomTemplate.KEY, "Gain 1M Fletching XP", l -> new BingoTiles.Ref(null, l, null), i -> false);
        assertFalse(custom.auto(), "a custom event's XP tile never moves by itself, so the hint mustn't say it does");
        assertTrue(custom.text().contains("only in bingo and scavenger hunts"), custom.text());
        assertTrue(PluginObjectiveMatcher.xpTiles(CustomTemplate.KEY, List.of(tile("t1", "Gain 1M Fletching XP", 1, 1))).isEmpty());
    }

    @Test
    void aTileCompletesFromTheLedgerAndARuneMetricsOnlyPlayerIsListed() {
        Event e = event(1, BingoTemplate.KEY);
        when(repository.objectives(1)).thenReturn(List.of(tile("t1", "Gain 1M Fletching XP", 1, 1), tile("t2", "Nex: Torva boots", 1, 1)));
        when(repository.startedAt(1)).thenReturn(Optional.of(STARTED));
        when(repository.participants(1)).thenReturn(List.of(player(ALICE), player(BOB)));
        when(repository.progress(1)).thenReturn(List.of());
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.aliasOf(BOB)).thenReturn(Optional.empty()); // RuneMetrics only: no RuneSync
        when(hub.xpGains(eq(GUILD), any(), isNull(), eq(STARTED), eq(NOW))).thenReturn(gains("a1_alice", FLETCHING, 1_200_000));

        assertEquals(1, tracker.feedTiles(e));
        verify(service).recordProgress(eq(GUILD), eq(1L), eq("t1"), eq(ALICE), eq(1L), eq(Source.AUTO), eq(PluginXpTracker.tileSourceKey(ALICE, "t1", 0)), isNull(), anyString());
        verify(service, never()).recordProgress(anyLong(), anyLong(), anyString(), eq(BOB), anyLong(), any(), anyString(), any(), anyString());
        assertEquals(Set.of(BOB), tracker.notCounted(1).orElseThrow().userIds());
    }

    @Test
    void aReReadAddsOnlyWhatIsNewAndTheKeyNamesWhatItBuildsOn() {
        Event e = event(2, ScavengerHuntTemplate.KEY);
        when(repository.objectives(2)).thenReturn(List.of(tile("s1", "500k XP in Mining", 500_000, 1)));
        when(repository.startedAt(2)).thenReturn(Optional.of(STARTED));
        when(repository.participants(2)).thenReturn(List.of(player(ALICE)));
        when(repository.progress(2)).thenReturn(List.of(new Progress(1, 2, "s1", ALICE, null, 100_000, Source.AUTO, "xpt:x", null, null, STARTED)));
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.xpGains(eq(GUILD), any(), isNull(), eq(STARTED), eq(NOW))).thenReturn(gains("a1_alice", MINING, 100_000));

        assertEquals(0, tracker.feedTiles(e), "nothing new since the last read");

        when(hub.xpGains(eq(GUILD), any(), isNull(), eq(STARTED), eq(NOW))).thenReturn(gains("a1_alice", MINING, 250_000));
        assertEquals(1, tracker.feedTiles(e));
        // two passes building on the same 100,000 get the same key whatever each read, so only one of them is written
        verify(service).recordProgress(eq(GUILD), eq(2L), eq("s1"), eq(ALICE), eq(150_000L), eq(Source.AUTO), eq("xpt:" + ALICE + ":s1:100000"), isNull(), anyString());
    }

    @Test
    void oneHubCallServesEverySkillTheTilesAskFor() {
        Event e = event(4, BingoTemplate.KEY);
        when(repository.objectives(4)).thenReturn(List.of(tile("t1", "Gain 1M Fletching XP", 1, 1), tile("t2", "500k XP in Mining", 1, 1), tile("t3", "Gain 2M XP", 1, 1)));
        when(repository.startedAt(4)).thenReturn(Optional.of(STARTED));
        when(repository.participants(4)).thenReturn(List.of(player(ALICE)));
        when(repository.progress(4)).thenReturn(List.of());
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.xpGains(eq(GUILD), any(), isNull(), eq(STARTED), eq(NOW))).thenReturn(gains("a1_alice", FLETCHING, 1_500_000, MINING, 600_000));

        assertEquals(3, tracker.feedTiles(e));
        verify(hub, times(1)).xpGains(anyLong(), any(), any(), any(), any());
        verify(service).recordProgress(eq(GUILD), eq(4L), eq("t3"), eq(ALICE), eq(1L), eq(Source.AUTO), anyString(), isNull(), anyString());
    }

    @Test
    void onlyXpGainedWhileTheEventRunsCounts() {
        Instant startsAt = STARTED.plusSeconds(600); // started 10 minutes early
        Instant endsAt = NOW.minusSeconds(60); // finished late
        Event e = event(5, BingoTemplate.KEY, startsAt, endsAt);
        when(repository.objectives(5)).thenReturn(List.of(tile("t1", "Gain 1M Fletching XP", 1, 1)));
        when(repository.startedAt(5)).thenReturn(Optional.of(STARTED));
        when(repository.participants(5)).thenReturn(List.of(player(ALICE), new Participant(2, BOB, GUILD, null, null, false, null, NOW.minusSeconds(30), null, null, null, null,
                null, null)));
        when(repository.progress(5)).thenReturn(List.of());
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.aliasOf(BOB)).thenReturn(Optional.of("a1_bob"));
        when(hub.xpGains(anyLong(), any(), any(), any(), any())).thenReturn(gains("a1_alice", FLETCHING, 10));

        tracker.feedTiles(e);
        verify(hub).xpGains(eq(GUILD), eq(List.of("a1_alice")), isNull(), eq(startsAt), eq(endsAt));
        verify(hub, times(1)).xpGains(anyLong(), any(), any(), any(), any()); // bob joined after the end: nothing to ask
    }

    @Test
    void takingTheLastXpTileOffClearsTheNotCountedList() {
        Event e = event(6, BingoTemplate.KEY);
        when(repository.objectives(6)).thenReturn(List.of(tile("t1", "Gain 1M Fletching XP", 1, 1)));
        when(repository.startedAt(6)).thenReturn(Optional.of(STARTED));
        when(repository.participants(6)).thenReturn(List.of(player(BOB)));
        when(repository.progress(6)).thenReturn(List.of());
        when(hub.aliasOf(BOB)).thenReturn(Optional.empty());
        tracker.feedTiles(e);
        assertEquals(Set.of(BOB), tracker.notCounted(6).orElseThrow().userIds());

        when(repository.objectives(6)).thenReturn(List.of(tile("t2", "Nex: Torva boots", 1, 1)));
        tracker.feedTiles(e);
        assertTrue(tracker.notCounted(6).isEmpty());
    }

    @Test
    void thePublicPageListsEachTileOnceAndNeverGivesAHuntStepAway() {
        List<ObjectiveSpec> board = List.of(tile("t1", "Gain 1M Fletching XP", 1, 1), tile("t2", "Gain 1M Fletching XP", 1, 1), tile("t3", "Nex: Torva boots", 1, 1));
        assertEquals(List.of("Gain 1M Fletching XP"), PluginObjectiveMatcher.shownXpTiles(BingoTemplate.KEY, Status.SIGNUPS, board));

        List<ObjectiveSpec> hunt = List.of(new ObjectiveSpec("item-1", "500k XP in Mining", "", 1, Evidence.EITHER, 1, 1, List.of()),
                new ObjectiveSpec("item-2", "Gain 1M Fletching XP", "", 1, Evidence.EITHER, 1, 1, List.of("item-1")));
        assertEquals(List.of(), PluginObjectiveMatcher.shownXpTiles(ScavengerHuntTemplate.KEY, Status.SIGNUPS, hunt), "not before the hunt starts");
        assertEquals(List.of("500k XP in Mining"), PluginObjectiveMatcher.shownXpTiles(ScavengerHuntTemplate.KEY, Status.RUNNING, hunt), "not a step still locked");
        assertEquals(List.of("500k XP in Mining", "Gain 1M Fletching XP"), PluginObjectiveMatcher.shownXpTiles(ScavengerHuntTemplate.KEY, Status.DONE, hunt));
    }

    @Test
    void theSweepLeavesTilesAloneWhileTheSwitchIsOff() {
        PluginXpTracker off = new PluginXpTracker(repository, service, hub).withClock(Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.runningEvents(anyString())).thenReturn(List.of());
        off.sweep();
        verify(repository, never()).runningEvents(BingoTemplate.KEY);
        tracker.sweep();
        verify(repository).runningEvents(BingoTemplate.KEY);
        verify(repository).runningEvents(ScavengerHuntTemplate.KEY);
    }

    @Test
    void eventsWithoutXpTilesCostNoHubCall() {
        Event e = event(3, BingoTemplate.KEY);
        when(repository.objectives(3)).thenReturn(List.of(tile("t1", "Nex: Torva boots", 1, 1)));
        assertEquals(0, tracker.feedTiles(e));
        verify(hub, never()).xpGains(anyLong(), any(), any(), any(), any());
        assertTrue(tracker.notCounted(3).isEmpty());
    }
}
