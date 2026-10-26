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
        return new Event(id, GUILD, template, "Bingo", "", Status.RUNNING, null, ScoringMode.INDIVIDUAL, STARTED, NOW.plusSeconds(86_400), 0, "{}", 1,
                STARTED.minusSeconds(7200), null, false);
    }

    private static Participant player(long userId) {
        return new Participant(1, userId, GUILD, null, null, false, null, STARTED.minusSeconds(60), null, null, null, null, null, null);
    }

    private static RuneClansHubClient.Answer gains(String alias, long xp) {
        return new RuneClansHubClient.Answer(200, DataObject.empty().put("players", DataArray.empty().add(DataObject.empty().put("alias", alias).put("xp", xp)))
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
        PluginObjectiveMatcher.offerXpTiles(true);
        var on = PluginObjectiveMatcher.explain(BingoTemplate.KEY, "Gain 1M Fletching XP", labels, i -> false);
        assertTrue(on.auto());
        assertTrue(on.text().contains("RuneMetrics only"), on.text());
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
        when(hub.xpGains(eq(GUILD), any(), eq(FLETCHING), eq(STARTED), eq(NOW))).thenReturn(gains("a1_alice", 1_200_000));

        assertEquals(1, tracker.feedTiles(e));
        verify(service).recordProgress(eq(GUILD), eq(1L), eq("t1"), eq(ALICE), eq(1L), eq(Source.AUTO), eq(PluginXpTracker.tileSourceKey(ALICE, "t1", NOW, 1)), isNull(), anyString());
        verify(service, never()).recordProgress(anyLong(), anyLong(), anyString(), eq(BOB), anyLong(), any(), anyString(), any(), anyString());
        assertEquals(Set.of(BOB), tracker.notCounted(1).orElseThrow().userIds());
    }

    @Test
    void aReReadAddsOnlyWhatIsNewAndTheKeyNamesTheDay() {
        Event e = event(2, ScavengerHuntTemplate.KEY);
        when(repository.objectives(2)).thenReturn(List.of(tile("s1", "500k XP in Mining", 500_000, 1)));
        when(repository.startedAt(2)).thenReturn(Optional.of(STARTED));
        when(repository.participants(2)).thenReturn(List.of(player(ALICE)));
        when(repository.progress(2)).thenReturn(List.of(new Progress(1, 2, "s1", ALICE, null, 100_000, Source.AUTO, "xpt:x", null, null, STARTED)));
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.xpGains(eq(GUILD), any(), eq(MINING), eq(STARTED), eq(NOW))).thenReturn(gains("a1_alice", 100_000));

        assertEquals(0, tracker.feedTiles(e), "nothing new since the last read");

        when(hub.xpGains(eq(GUILD), any(), eq(MINING), eq(STARTED), eq(NOW))).thenReturn(gains("a1_alice", 250_000));
        assertEquals(1, tracker.feedTiles(e));
        verify(service).recordProgress(eq(GUILD), eq(2L), eq("s1"), eq(ALICE), eq(150_000L), eq(Source.AUTO), eq("xpt:" + ALICE + ":s1:2026-10-20:250000"), isNull(), anyString());
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
