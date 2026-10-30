package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRepository.Progress;
import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Source;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-706: XP tile passes make a bounded number of hub calls (coalesced starts, a per-pass budget, stop at the first failure), a moved team member
 * counts for the new team from the move, a final pass after the move to scoring keeps the last minutes, and competitions read clan-vs-clan guests
 * under their own server.
 */
class XpTilesBoundedTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long ALICE = Long.parseLong("1".repeat(18));
    private static final long BOB = Long.parseLong("2".repeat(18));
    private static final Instant STARTED = Instant.parse("2026-10-20T18:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-20T22:00:30Z");
    private static final int FLETCHING = 9;

    private final EventRepository repository = Mockito.mock(EventRepository.class);
    private final EventService service = Mockito.mock(EventService.class);
    private final RuneClansHubClient hub = Mockito.mock(RuneClansHubClient.class);
    private final PluginXpTracker tracker = new PluginXpTracker(repository, service, hub).withClock(Clock.fixed(NOW, ZoneOffset.UTC)).withTiles(true);

    XpTilesBoundedTest() {
        when(service.mayPlay(any(), any())).thenReturn(true);
    }

    @AfterEach
    void offAgain() {
        PluginObjectiveMatcher.offerXpTiles(false);
    }

    private static Event event(long id, String template, Status status, ScoringMode mode, Instant endsAt) {
        return new Event(id, GUILD, template, "Hunt", "", status, null, mode, STARTED, endsAt, 0, "{}", 1, STARTED.minusSeconds(7200), null, false);
    }

    private static Participant member(long userId, long guildId, Long teamId) {
        return new Participant(1, userId, guildId, teamId, null, false, null, STARTED.minusSeconds(60), null, null, null, null, null, null);
    }

    private static long user(int i) {
        return 100_000L + i;
    }

    private static String aliasOf(long userId) {
        return "a1_" + userId;
    }

    /** A hub that answers every gains call with {@code xp} Fletching XP for each alias asked. */
    private void hubGives(long xp) {
        when(hub.xpGains(anyLong(), any(), any(), any(), any())).thenAnswer(call -> {
            DataArray players = DataArray.empty();
            for (String alias : call.<Collection<String>>getArgument(1)) {
                players.add(DataObject.empty().put("alias", alias).put("xp", xp)
                        .put("bySkill", DataArray.empty().add(DataObject.empty().put("skillId", FLETCHING).put("xp", xp))));
            }
            return new RuneClansHubClient.Answer(200, DataObject.empty().put("players", players).put("notCounted", DataArray.empty()));
        });
    }

    /** The {@code from} of every gains call made so far, in order. */
    private List<Instant> froms() {
        List<Instant> froms = new ArrayList<>();
        mockingDetails(hub).getInvocations().stream().filter(i -> i.getMethod().getName().equals("xpGains")).forEach(i -> froms.add(i.getArgument(3)));
        return froms;
    }

    @Test
    void aStartIsRoundedUpOnAFiveMinuteGridFromTheEventsStart() {
        assertEquals(STARTED, PluginXpTracker.coalesced(STARTED, STARTED));
        assertEquals(STARTED.minusSeconds(60), PluginXpTracker.coalesced(STARTED, STARTED.minusSeconds(60)), "before the start: left alone");
        assertEquals(STARTED.plusSeconds(300), PluginXpTracker.coalesced(STARTED, STARTED.plusNanos(1)), "never earlier than the unlock");
        assertEquals(STARTED.plusSeconds(300), PluginXpTracker.coalesced(STARTED, STARTED.plusSeconds(299)));
        assertEquals(STARTED.plusSeconds(300), PluginXpTracker.coalesced(STARTED, STARTED.plusSeconds(300)));
        assertEquals(STARTED.plusSeconds(600), PluginXpTracker.coalesced(STARTED, STARTED.plusSeconds(301)));
        Instant odd = Instant.parse("2026-10-20T18:03:17Z");
        assertEquals(odd.plusSeconds(300), PluginXpTracker.coalesced(odd, odd.plusSeconds(42)), "the grid starts at the event's own start");
    }

    @Test
    void aTwoHundredPlayerOrderedHuntMakesAHandfulOfCallsPerPassAndReadsEveryoneInTurn() {
        Event e = event(1, ScavengerHuntTemplate.KEY, Status.RUNNING, ScoringMode.INDIVIDUAL, NOW.plusSeconds(86_400));
        ObjectiveSpec first = new ObjectiveSpec("item-1", "Nex: Torva boots", "", 1, Evidence.EITHER, 1, 1, List.of());
        ObjectiveSpec second = new ObjectiveSpec("item-2", "Gain 1M Fletching XP", "", 1, Evidence.EITHER, 1, 1, List.of("item-1"));
        when(repository.objectives(1)).thenReturn(List.of(first, second));
        when(repository.startedAt(1)).thenReturn(Optional.of(STARTED));
        List<Participant> players = new ArrayList<>();
        List<Progress> progress = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            players.add(member(user(i), GUILD, null));
            // each player's step 2 unlocked at its own minute (and second): 200 different starts of counting
            progress.add(new Progress(i, 1, "item-1", user(i), null, 1, Source.AUTO, "drop:" + i, null, null, STARTED.plusSeconds(60L * i + 7)));
            when(hub.aliasOf(user(i))).thenReturn(Optional.of(aliasOf(user(i))));
        }
        when(repository.participants(1)).thenReturn(players);
        when(repository.progress(1)).thenReturn(progress);
        hubGives(2_000_000);

        Set<Instant> asked = new HashSet<>();
        for (int pass = 0; pass < 7; pass++) {
            clearInvocations(hub);
            tracker.feedTiles(e);
            List<Instant> froms = froms();
            assertTrue(froms.size() <= PluginXpTracker.MAX_TILE_CALLS_PER_PASS, "pass " + pass + " made " + froms.size() + " hub calls");
            for (Instant from : froms) assertEquals(0, Duration.between(STARTED, from).toSeconds() % 300, "on the five-minute grid: " + from);
            asked.addAll(froms);
        }
        // 200 unlocks over 200 minutes share 40 five-minute starts, and seven passes of six reads cover them all
        assertEquals(40, asked.size());
        for (int i = 0; i < 200; i++) {
            verify(service, atLeastOnce()).recordProgress(eq(GUILD), eq(1L), eq("item-2"), eq(user(i)), eq(1L), eq(Source.AUTO), anyString(), isNull(), anyString());
        }
    }

    @Test
    void theFirstFailedHubCallEndsThePass() {
        Event e = event(2, BingoTemplate.KEY, Status.RUNNING, ScoringMode.INDIVIDUAL, NOW.plusSeconds(86_400));
        when(repository.objectives(2)).thenReturn(List.of(new ObjectiveSpec("t1", "Gain 1M Fletching XP", "", 1, Evidence.EITHER, 1, 1, List.of())));
        when(repository.startedAt(2)).thenReturn(Optional.of(STARTED));
        // three players who joined late, far apart: three starts of counting
        List<Participant> players = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            players.add(new Participant(2, user(i), GUILD, null, null, false, null, STARTED.plusSeconds(3600L * i + 60), null, null, null, null, null, null));
            when(hub.aliasOf(user(i))).thenReturn(Optional.of(aliasOf(user(i))));
        }
        when(repository.participants(2)).thenReturn(players);
        when(repository.progress(2)).thenReturn(List.of());
        when(hub.xpGains(anyLong(), any(), any(), any(), any())).thenReturn(new RuneClansHubClient.Answer(503, null));

        assertEquals(0, tracker.feedTiles(e));
        verify(hub, times(1)).xpGains(anyLong(), any(), any(), any(), any());
        assertTrue(tracker.notCounted(2).isEmpty(), "nothing is said about who counts when the hub couldn't be asked");
    }

    @Test
    void aWholeTeamAddsUpWhenItsMembersAreReadOnDifferentPasses() {
        long red = 41;
        Event e = event(3, BingoTemplate.KEY, Status.RUNNING, ScoringMode.TEAM, NOW.plusSeconds(86_400));
        when(repository.objectives(3)).thenReturn(List.of(new ObjectiveSpec("t1", "Gain 1M Fletching XP", "", 1, Evidence.EITHER, 1, 1, List.of())));
        when(repository.startedAt(3)).thenReturn(Optional.of(STARTED));
        // seven members who joined an hour apart: one more start than a pass reads
        List<Participant> players = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            players.add(new Participant(3, user(i), GUILD, red, null, false, null, STARTED.plusSeconds(1800L * i + 60), null, null, null, null, null, null));
            when(hub.aliasOf(user(i))).thenReturn(Optional.of(aliasOf(user(i))));
        }
        when(repository.participants(3)).thenReturn(players);
        when(repository.progress(3)).thenReturn(List.of());
        hubGives(150_000);

        assertEquals(0, tracker.feedTiles(e), "six of seven read: 900k, not yet 1M");
        assertEquals(PluginXpTracker.MAX_TILE_CALLS_PER_PASS, froms().size());
        clearInvocations(hub);
        assertEquals(1, tracker.feedTiles(e), "the seventh is read now and the other six count from their last answer: 1.05M");
        verify(service).recordProgress(eq(GUILD), eq(3L), eq("t1"), anyLong(), eq(1L), eq(Source.AUTO), eq("xpt:t" + red + ":t1:0"), isNull(), anyString());
        assertTrue(froms().contains(STARTED.plusSeconds(1800L * 6 + 300)), "the span never read goes first");
    }

    @Test
    void aMemberMovedToAnotherTeamCountsForItFromTheMove() {
        long red = 41, blue = 42;
        Instant moved = STARTED.plusSeconds(3 * 3600 + 120);
        Event e = event(4, BingoTemplate.KEY, Status.RUNNING, ScoringMode.TEAM, NOW.plusSeconds(86_400));
        when(repository.objectives(4)).thenReturn(List.of(new ObjectiveSpec("t1", "Gain 1M Fletching XP", "", 1, Evidence.EITHER, 1, 1, List.of())));
        when(repository.startedAt(4)).thenReturn(Optional.of(STARTED));
        when(repository.participants(4)).thenReturn(List.of(member(ALICE, GUILD, red), member(BOB, GUILD, blue)));
        when(repository.teamJoinedAt(4)).thenReturn(Map.of(BOB, moved, ALICE, STARTED.minusSeconds(600)));
        when(repository.progress(4)).thenReturn(List.of());
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.aliasOf(BOB)).thenReturn(Optional.of("a1_bob"));
        hubGives(500_000);

        tracker.feedTiles(e);
        // bob's XP before the move counted for red; blue reads him from the move (rounded up), alice (on her team since before the start) from the start
        verify(hub).xpGains(eq(GUILD), eq(List.of("a1_bob")), isNull(), eq(STARTED.plusSeconds(3 * 3600 + 300)), eq(NOW));
        verify(hub).xpGains(eq(GUILD), eq(List.of("a1_alice")), isNull(), eq(STARTED), eq(NOW));
    }

    @Test
    void theFinalPassAfterTheMoveToScoringKeepsTheLastMinutesOnce() {
        Instant end = STARTED.plusSeconds(4 * 3600);
        Instant scoring = end.plusSeconds(20); // the organiser finished on time; NOW is 10 seconds later
        Instant unlocked = end.minusSeconds(100); // step 2 unlocked a minute and a half before the end
        Event e = event(5, ScavengerHuntTemplate.KEY, Status.SCORING, ScoringMode.INDIVIDUAL, end);
        ObjectiveSpec first = new ObjectiveSpec("item-1", "Nex: Torva boots", "", 1, Evidence.EITHER, 1, 1, List.of());
        ObjectiveSpec second = new ObjectiveSpec("item-2", "Gain 1M Fletching XP", "", 1, Evidence.EITHER, 1, 1, List.of("item-1"));
        when(repository.objectives(5)).thenReturn(List.of(first, second));
        when(repository.startedAt(5)).thenReturn(Optional.of(STARTED));
        when(repository.scoringAt(5)).thenReturn(Optional.of(scoring));
        when(repository.participants(5)).thenReturn(List.of(member(ALICE, GUILD, null)));
        when(repository.progress(5)).thenReturn(List.of(new Progress(1, 5, "item-1", ALICE, null, 1, Source.AUTO, "drop:1", null, null, unlocked)));
        when(repository.runningEvents(any())).thenReturn(List.of());
        when(repository.scoringSince(eq(ScavengerHuntTemplate.KEY), any())).thenReturn(List.of(e));
        when(repository.scoringSince(eq(BingoTemplate.KEY), any())).thenReturn(List.of());
        when(repository.scoringSince(eq(ClanVsClan.KEY), any())).thenReturn(List.of());
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(repository.runningEvents(CompetitionTemplate.KEY)).thenReturn(List.of());
        hubGives(1_200_000);

        tracker.sweep();
        // read exactly from the unlock (no rounding past the end) up to the planned end
        verify(hub).xpGains(eq(GUILD), eq(List.of("a1_alice")), isNull(), eq(unlocked), eq(end));
        verify(service).recordProgress(eq(GUILD), eq(5L), eq("item-2"), eq(ALICE), eq(1L), eq(Source.AUTO), anyString(), isNull(), anyString());
        verify(repository).scoringSince(eq(ScavengerHuntTemplate.KEY), eq(NOW.minus(PluginXpTracker.FINAL_WINDOW)));

        clearInvocations(hub);
        tracker.sweep();
        verify(hub, never()).xpGains(anyLong(), any(), any(), any(), any());
    }

    @Test
    void theFinalPassReadsNoFurtherThanTheMoveToScoringWhenTheOrganiserFinishedEarly() {
        Instant scoring = NOW.minusSeconds(120);
        Event e = event(6, BingoTemplate.KEY, Status.SCORING, ScoringMode.INDIVIDUAL, NOW.plusSeconds(86_400));
        when(repository.objectives(6)).thenReturn(List.of(new ObjectiveSpec("t1", "Gain 1M Fletching XP", "", 1, Evidence.EITHER, 1, 1, List.of())));
        when(repository.startedAt(6)).thenReturn(Optional.of(STARTED));
        when(repository.scoringAt(6)).thenReturn(Optional.of(scoring));
        when(repository.participants(6)).thenReturn(List.of(member(ALICE, GUILD, null)));
        when(repository.progress(6)).thenReturn(List.of());
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        hubGives(10);

        tracker.feedTiles(e);
        verify(hub).xpGains(eq(GUILD), eq(List.of("a1_alice")), isNull(), eq(STARTED), eq(scoring));
    }

    @Test
    void anEventBeingScoredTakesOnlyTheFinalXpTilePass() {
        Event scoring = event(7, BingoTemplate.KEY, Status.SCORING, ScoringMode.INDIVIDUAL, NOW);
        Event running = event(7, BingoTemplate.KEY, Status.RUNNING, ScoringMode.INDIVIDUAL, NOW);
        assertTrue(EventService.finalXpTile(scoring, Source.AUTO, PluginXpTracker.tileSourceKey("1", "t1", 0)));
        assertFalse(EventService.finalXpTile(scoring, Source.AUTO, "drop:1"), "a plugin drop after the finish doesn't count");
        assertFalse(EventService.finalXpTile(scoring, Source.MANUAL, PluginXpTracker.tileSourceKey("1", "t1", 0)));
        assertFalse(EventService.finalXpTile(scoring, Source.AUTO, null));
        assertFalse(EventService.finalXpTile(running, Source.AUTO, PluginXpTracker.tileSourceKey("1", "t1", 0)), "a running event takes it the normal way");
    }

    @Test
    void aCompetitionReadsAClanVsClanGuestUnderTheirOwnServer() {
        long guest = Long.parseLong("8".repeat(18));
        Event e = new Event(8, GUILD, CompetitionTemplate.KEY, "Comp", "", Status.RUNNING, null, ScoringMode.INDIVIDUAL, STARTED, NOW.plusSeconds(86_400), 0,
                "{\"skillId\":9,\"xpSource\":\"plugin\"}", 1, STARTED.minusSeconds(7200), null, false);
        when(repository.startedAt(8)).thenReturn(Optional.of(STARTED));
        when(repository.participants(8)).thenReturn(List.of(member(ALICE, GUILD, null), member(BOB, guest, null)));
        when(repository.progress(8)).thenReturn(List.of());
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.aliasOf(BOB)).thenReturn(Optional.of("a1_bob"));
        hubGives(1_000);

        assertEquals(2, tracker.feed(e));
        verify(hub).xpGains(eq(GUILD), eq(List.of("a1_alice")), eq(FLETCHING), eq(STARTED), eq(NOW));
        verify(hub).xpGains(eq(guest), eq(List.of("a1_bob")), eq(FLETCHING), eq(STARTED), eq(NOW));
        // the progress goes to the event, in the host's server
        verify(service).recordProgress(eq(GUILD), eq(8L), eq(CompetitionTemplate.OBJECTIVE_KEY), eq(BOB), eq(1_000L), eq(Source.IMPORT), anyString(), isNull(), anyString());
    }

    /** A clock the test moves on. */
    private static final class MovingClock extends Clock {
        Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void aSpanThatKeepsFailingGoesBehindTheOthersInsteadOfStarvingThem() {
        MovingClock clock = new MovingClock(NOW);
        PluginXpTracker moving = new PluginXpTracker(repository, service, hub).withClock(clock).withTiles(true);
        Event e = event(9, BingoTemplate.KEY, Status.RUNNING, ScoringMode.INDIVIDUAL, NOW.plusSeconds(86_400));
        when(repository.objectives(9)).thenReturn(List.of(new ObjectiveSpec("t1", "Gain 1M Fletching XP", "", 1, Evidence.EITHER, 1, 1, List.of())));
        when(repository.startedAt(9)).thenReturn(Optional.of(STARTED));
        List<Participant> players = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            players.add(new Participant(9, user(i), GUILD, null, null, false, null, STARTED.plusSeconds(3600L * i + 60), null, null, null, null, null, null));
            when(hub.aliasOf(user(i))).thenReturn(Optional.of(aliasOf(user(i))));
        }
        when(repository.participants(9)).thenReturn(players);
        when(repository.progress(9)).thenReturn(List.of());
        hubGives(2_000_000);
        // the first player's span always fails (say the hub times out on it); the others are healthy
        Instant broken = STARTED.plusSeconds(300);
        Mockito.doReturn(new RuneClansHubClient.Answer(503, null)).when(hub).xpGains(anyLong(), any(), any(), eq(broken), any());

        assertEquals(0, moving.feedTiles(e), "the broken span is tried first and ends the pass");
        clock.now = NOW.plusSeconds(60);
        assertEquals(2, moving.feedTiles(e), "next minute it goes behind the two never tried, which are read before it fails again");
        verify(service).recordProgress(eq(GUILD), eq(9L), eq("t1"), eq(user(1)), eq(1L), eq(Source.AUTO), anyString(), isNull(), anyString());
        verify(service).recordProgress(eq(GUILD), eq(9L), eq("t1"), eq(user(2)), eq(1L), eq(Source.AUTO), anyString(), isNull(), anyString());
        clearInvocations(hub);
        clock.now = NOW.plusSeconds(120);
        moving.feedTiles(e);
        assertEquals(List.of(STARTED.plusSeconds(3900), STARTED.plusSeconds(7500), broken), froms(), "asked longest ago first, the broken one last");
    }

    @Test
    void anEventReopenedAndFinishedAgainGetsASecondFinalPass() {
        Instant end = STARTED.plusSeconds(4 * 3600);
        Event e = event(10, BingoTemplate.KEY, Status.SCORING, ScoringMode.INDIVIDUAL, end.plusSeconds(86_400));
        when(repository.objectives(10)).thenReturn(List.of(new ObjectiveSpec("t1", "Gain 1M Fletching XP", "", 1, Evidence.EITHER, 1, 1, List.of())));
        when(repository.startedAt(10)).thenReturn(Optional.of(STARTED));
        when(repository.participants(10)).thenReturn(List.of(member(ALICE, GUILD, null)));
        when(repository.progress(10)).thenReturn(List.of());
        when(repository.runningEvents(any())).thenReturn(List.of());
        when(repository.scoringSince(eq(BingoTemplate.KEY), any())).thenReturn(List.of(e));
        when(repository.scoringSince(eq(ScavengerHuntTemplate.KEY), any())).thenReturn(List.of());
        when(repository.scoringSince(eq(ClanVsClan.KEY), any())).thenReturn(List.of());
        when(hub.aliasOf(ALICE)).thenReturn(Optional.of("a1_alice"));
        hubGives(10);

        when(repository.scoringAt(10)).thenReturn(Optional.of(NOW.minusSeconds(300)));
        tracker.sweep();
        tracker.sweep();
        verify(hub, times(1)).xpGains(anyLong(), any(), any(), any(), any());
        // an organiser reopened it and finished it again between two passes: the new move to scoring gets its own final pass
        when(repository.scoringAt(10)).thenReturn(Optional.of(NOW.minusSeconds(20)));
        tracker.sweep();
        verify(hub, times(2)).xpGains(anyLong(), any(), any(), any(), any());
        verify(hub).xpGains(eq(GUILD), eq(List.of("a1_alice")), isNull(), eq(STARTED), eq(NOW.minusSeconds(20)));
    }
}
