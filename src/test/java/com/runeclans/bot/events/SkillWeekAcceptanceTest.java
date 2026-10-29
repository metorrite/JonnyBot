package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRepository.Progress;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Source;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.SkillWeekRules.Winner;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.runescape.PlayerLinkService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-541 acceptance: a week's winners are exactly the XP ledger's gains for that skill and span. The hub here answers {@code xp/gains} from a fixture
 * ledger (timed gains per player, skill and minute); the week's competition is fed through {@link PluginXpTracker} during the week and once more
 * at the close (only once the hub could be asked for everyone, or a day late), and the winners {@link SkillWeekService#close} keeps must equal what the ledger says, with gains in other skills, before Monday
 * and after Sunday left out, and a late joiner counted from Monday.
 */
class SkillWeekAcceptanceTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long ALICE = Long.parseLong("1".repeat(18));
    private static final long BOB = Long.parseLong("2".repeat(18));
    private static final long CAROL = Long.parseLong("3".repeat(18));
    private static final long DAVE = Long.parseLong("4".repeat(18));
    private static final int FLETCHING = 9;
    private static final int MINING = 14;
    private static final Instant START = Instant.parse("2026-10-26T00:00:00Z");
    private static final Instant END = SkillWeekRules.weekEnd(START);
    /** The hub's spans are [from, to): the week is asked up to the second after its last. */
    private static final Instant NEXT_MONDAY = Instant.parse("2026-11-02T00:00:00Z");

    private record Gain(String alias, int skill, Instant at, long xp) {}

    private static final List<Gain> LEDGER = List.of(
            new Gain("a1_alice", FLETCHING, Instant.parse("2026-10-26T10:00:00Z"), 5_000),
            new Gain("a1_alice", FLETCHING, Instant.parse("2026-11-01T23:00:00Z"), 3_000),
            new Gain("a1_alice", FLETCHING, Instant.parse("2026-11-02T00:03:00Z"), 100_000), // next week
            new Gain("a1_alice", MINING, Instant.parse("2026-10-28T12:00:00Z"), 50_000), // another skill
            new Gain("a1_bob", FLETCHING, Instant.parse("2026-10-27T09:00:00Z"), 7_000), // before bob joined: still counts
            new Gain("a1_bob", FLETCHING, Instant.parse("2026-10-31T18:00:00Z"), 2_000),
            new Gain("a1_carol", FLETCHING, Instant.parse("2026-10-25T23:59:00Z"), 40_000), // last week
            new Gain("a1_carol", FLETCHING, Instant.parse("2026-10-30T20:00:00Z"), 6_000),
            new Gain("a1_carol", FLETCHING, Instant.parse("2026-11-01T23:59:59.500Z"), 1_000), // the week's last second still counts
            new Gain("a1_dave", FLETCHING, Instant.parse("2026-10-28T08:00:00Z"), 8_999));

    private final EventRepository repository = Mockito.mock(EventRepository.class);
    private final EventService events = Mockito.mock(EventService.class);
    private final RuneClansHubClient hub = Mockito.mock(RuneClansHubClient.class);
    private final SkillWeekRepository weeks = Mockito.mock(SkillWeekRepository.class);
    private final List<Progress> progress = new ArrayList<>();

    /** What the ledger says each alias gained in {@code skill} from {@code from} up to (not including) {@code to}, as the hub counts. */
    private static Map<String, Long> ledger(Collection<String> aliases, int skill, Instant from, Instant to) {
        Map<String, Long> out = new HashMap<>();
        for (Gain g : LEDGER) {
            if (aliases.contains(g.alias()) && g.skill() == skill && !g.at().isBefore(from) && g.at().isBefore(to)) out.merge(g.alias(), g.xp(), Long::sum);
        }
        return out;
    }

    private final Event event = new Event(1, GUILD, CompetitionTemplate.KEY, "Skill of the week", "", Status.RUNNING, null, ScoringMode.INDIVIDUAL, START, END, 0,
            "{\"skillId\":9,\"xpPerPoint\":1000000,\"xpSource\":\"plugin\",\"wholeSpan\":true}", 1, START, null, false);
    private boolean hubUp = true;

    @SuppressWarnings("unchecked")
    private void fixture() {
        when(repository.event(GUILD, 1)).thenReturn(Optional.of(event));
        when(repository.startedAt(1)).thenReturn(Optional.of(START.plusSeconds(300))); // the pass that opened it ran a few minutes in
        when(repository.participants(1)).thenReturn(List.of(player(ALICE, START), player(BOB, Instant.parse("2026-10-29T12:00:00Z")), player(CAROL, START), player(DAVE, START)));
        when(repository.progress(1)).thenAnswer(i -> List.copyOf(progress));
        for (long user : List.of(ALICE, BOB, CAROL, DAVE)) {
            String name = user == ALICE ? "alice" : user == BOB ? "bob" : user == CAROL ? "carol" : "dave";
            when(hub.aliasOf(user)).thenReturn(Optional.of("a1_" + name));
        }
        when(hub.xpGains(eq(GUILD), any(Collection.class), any(), any(Instant.class), any(Instant.class))).thenAnswer(i -> {
            if (!hubUp) return new RuneClansHubClient.Answer(503, null);
            Collection<String> aliases = i.getArgument(1);
            DataArray rows = DataArray.empty();
            ledger(aliases, i.getArgument(2), i.getArgument(3), i.getArgument(4)).forEach((alias, xp) -> rows.add(DataObject.empty().put("alias", alias).put("xp", xp)));
            return new RuneClansHubClient.Answer(200, DataObject.empty().put("players", rows).put("notCounted", DataArray.empty()));
        });
        when(events.recordProgress(eq(GUILD), eq(1L), eq(CompetitionTemplate.OBJECTIVE_KEY), anyLong(), anyLong(), eq(Source.IMPORT), anyString(), any(), anyString())).thenAnswer(i -> {
            progress.add(new Progress(progress.size() + 1, 1, CompetitionTemplate.OBJECTIVE_KEY, i.getArgument(3), null, i.getArgument(4), Source.IMPORT, i.getArgument(6), null, null, START));
            return null;
        });
        when(weeks.close(eq(GUILD), eq(START), anyString())).thenReturn(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theWeeksWinnersAreTheLedgersGainsForThatSkillAndSpan() {
        fixture();
        PluginXpTracker tracker = new PluginXpTracker(repository, events, hub);
        // passes during the week, then the close a little after Sunday ends (when the next week's gains are already in the ledger)
        tracker.withClock(Clock.fixed(Instant.parse("2026-10-28T09:00:00Z"), ZoneOffset.UTC)).feed(event);
        tracker.withClock(Clock.fixed(Instant.parse("2026-10-31T19:00:00Z"), ZoneOffset.UTC)).feed(event);
        Instant closing = Instant.parse("2026-11-02T00:12:00Z");
        tracker.withClock(Clock.fixed(closing, ZoneOffset.UTC));
        SkillWeekService service = new SkillWeekService(weeks, events, repository, tracker, hub, Mockito.mock(PlayerLinkService.class), Mockito.mock(com.runeclans.bot.member.MemberProfileRepository.class),
                Mockito.mock(com.runeclans.bot.plugin.PluginVisibilityRepository.class), Mockito.mock(com.runeclans.bot.discord.jobs.DiscordOutbox.class)).withClock(Clock.fixed(closing, ZoneOffset.UTC));
        List<Winner> winners = service.close(new SkillWeekRepository.Week(GUILD, START, FLETCHING, 1L, null, null)).orElseThrow();

        Map<String, Long> truth = ledger(List.of("a1_alice", "a1_bob", "a1_carol", "a1_dave"), FLETCHING, START, NEXT_MONDAY);
        Map<Long, Long> byUser = Map.of(ALICE, truth.get("a1_alice"), BOB, truth.get("a1_bob"), CAROL, truth.get("a1_carol"), DAVE, truth.get("a1_dave"));
        assertEquals(SkillWeekRules.winners(byUser, SkillWeekRules.WINNERS), winners);
        assertEquals(List.of(new Winner(1, BOB, 9_000), new Winner(2, DAVE, 8_999), new Winner(3, ALICE, 8_000)), winners);
        assertEquals(byUser, service.xpByUser(1), "every player's total is the ledger's, not only the winners'");
        org.mockito.ArgumentCaptor<String> kept = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(weeks).close(eq(GUILD), eq(START), kept.capture());
        DataArray stored = DataArray.fromJson(kept.getValue());
        assertEquals(winners.size(), stored.length());
        for (int i = 0; i < winners.size(); i++) {
            assertEquals(winners.get(i).rank(), stored.getObject(i).getInt("rank"));
            assertEquals(String.valueOf(winners.get(i).userId()), stored.getObject(i).getString("userId"));
            assertEquals(winners.get(i).xp(), stored.getObject(i).getLong("xp"));
        }
        verify(hub, Mockito.atLeastOnce()).xpGains(eq(GUILD), any(Collection.class), eq(FLETCHING), eq(START), eq(NEXT_MONDAY));
        verify(events).moveStage(GUILD, 1, Status.SCORING);
        verify(events).moveStage(GUILD, 1, Status.DONE);
    }

    @Test
    void aWeekIsntClosedWhileTheHubCantBeAskedThenClosedADayLate() {
        fixture();
        PluginXpTracker tracker = new PluginXpTracker(repository, events, hub);
        tracker.withClock(Clock.fixed(Instant.parse("2026-10-31T19:00:00Z"), ZoneOffset.UTC)).feed(event);
        // the hub is down from Sunday 23:00 until after the first closing passes: Alice's 3,000 at 23:00 isn't in yet
        hubUp = false;
        Instant closing = Instant.parse("2026-11-02T00:12:00Z");
        tracker.withClock(Clock.fixed(closing, ZoneOffset.UTC));
        SkillWeekRepository.Week week = new SkillWeekRepository.Week(GUILD, START, FLETCHING, 1L, null, null);
        SkillWeekService service = new SkillWeekService(weeks, events, repository, tracker, hub, Mockito.mock(PlayerLinkService.class), Mockito.mock(com.runeclans.bot.member.MemberProfileRepository.class),
                Mockito.mock(com.runeclans.bot.plugin.PluginVisibilityRepository.class), Mockito.mock(com.runeclans.bot.discord.jobs.DiscordOutbox.class)).withClock(Clock.fixed(closing, ZoneOffset.UTC));
        assertTrue(service.close(week).isEmpty());
        verify(weeks, never()).close(anyLong(), any(), anyString());
        verify(events, never()).moveStage(GUILD, 1, Status.SCORING);

        // back up at 00:20: the next pass closes it with the ledger's numbers
        hubUp = true;
        Instant later = Instant.parse("2026-11-02T00:22:00Z");
        tracker.withClock(Clock.fixed(later, ZoneOffset.UTC));
        List<Winner> winners = service.withClock(Clock.fixed(later, ZoneOffset.UTC)).close(week).orElseThrow();
        assertEquals(new Winner(3, ALICE, 8_000), winners.get(2));
        verify(events).moveStage(GUILD, 1, Status.DONE);
    }

    @Test
    void aHubThatStaysDownForADayClosesTheWeekWithWhatItHas() {
        fixture();
        hubUp = false;
        when(hub.aliasOf(ALICE)).thenThrow(new RuneClansHubClient.Unreachable("down"));
        Instant late = Instant.parse("2026-11-03T00:00:00Z");
        PluginXpTracker tracker = new PluginXpTracker(repository, events, hub).withClock(Clock.fixed(late, ZoneOffset.UTC));
        assertFalse(tracker.pass(event).complete());
        SkillWeekService service = new SkillWeekService(weeks, events, repository, tracker, hub, Mockito.mock(PlayerLinkService.class), Mockito.mock(com.runeclans.bot.member.MemberProfileRepository.class),
                Mockito.mock(com.runeclans.bot.plugin.PluginVisibilityRepository.class), Mockito.mock(com.runeclans.bot.discord.jobs.DiscordOutbox.class)).withClock(Clock.fixed(late, ZoneOffset.UTC));
        assertEquals(List.of(), service.close(new SkillWeekRepository.Week(GUILD, START, FLETCHING, 1L, null, null)).orElseThrow());
        verify(weeks).close(GUILD, START, "[]");
    }

    @Test
    void aHubThatCantLookUpLinksIsAskedOncePerPassAndExpiredAliasesStillCount() {
        fixture();
        PluginXpTracker tracker = new PluginXpTracker(repository, events, hub).withClock(Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC));
        assertTrue(tracker.pass(event).complete());
        verify(hub, times(4)).aliasOf(anyLong());

        // the kept aliases have expired and the hub can't look links up: one try, then the expired aliases, and the pass isn't complete
        when(hub.aliasOf(anyLong())).thenThrow(new RuneClansHubClient.Unreachable("down"));
        Instant closing = Instant.parse("2026-11-02T00:12:00Z");
        assertFalse(tracker.withClock(Clock.fixed(closing, ZoneOffset.UTC)).pass(event).complete());
        verify(hub, times(5)).aliasOf(anyLong());
        Map<Long, Long> fed = new HashMap<>();
        for (Progress p : progress) fed.merge(p.userId(), p.amount(), Long::sum);
        assertEquals(Map.of(ALICE, 8_000L, BOB, 9_000L, CAROL, 7_000L, DAVE, 8_999L), fed, "everyone still counts");
        assertEquals(java.util.Set.of(), tracker.notCounted(1).orElseThrow().userIds());

        // a moment later it isn't asked again yet
        assertFalse(tracker.withClock(Clock.fixed(closing.plusSeconds(30), ZoneOffset.UTC)).pass(event).complete());
        verify(hub, times(5)).aliasOf(anyLong());
    }

    @Test
    void withNoAliasKeptAHubThatIsDownIsAskedOnceAndNobodyIsCalledUnlinked() {
        fixture();
        when(hub.aliasOf(anyLong())).thenThrow(new RuneClansHubClient.Unreachable("down"));
        PluginXpTracker tracker = new PluginXpTracker(repository, events, hub).withClock(Clock.fixed(Instant.parse("2026-11-02T00:12:00Z"), ZoneOffset.UTC));
        PluginXpTracker.Pass pass = tracker.pass(event);
        assertFalse(pass.complete());
        assertEquals(0, pass.updated());
        verify(hub, times(1)).aliasOf(anyLong());
        verify(hub, never()).xpGains(anyLong(), any(), any(), any(), any());
        assertEquals(java.util.Set.of(), tracker.notCounted(1).orElseThrow().userIds(), "the hub couldn't be asked: nobody is listed as having no RuneSync");
    }

    @Test
    void theCompetitionsOwnStandingsAreInTheWinnersOrder() {
        fixture();
        PluginXpTracker tracker = new PluginXpTracker(repository, events, hub).withClock(Clock.fixed(Instant.parse("2026-11-02T00:12:00Z"), ZoneOffset.UTC));
        tracker.feed(event);
        // Bob 9,000, Dave 8,999, Alice 8,000, Carol 7,000: all under a completion of the objective (1M XP), so the usual standings tie at 0 points
        List<ObjectiveSpec> objectives = new CompetitionTemplate().starterObjectives(DataObject.fromJson(event.config()));
        List<EventScoring.Entry> log = new ArrayList<>();
        for (Progress p : progress) log.add(new EventScoring.Entry("u:" + p.userId(), p.objectiveKey(), p.amount(), p.createdAt()));
        List<EventScoring.Standing> usual = EventScoring.standings(objectives, log);
        assertTrue(usual.stream().allMatch(x -> x.points() == 0));

        List<EventScoring.Standing> own = new CompetitionTemplate().standings(event, objectives, List.copyOf(progress), usual);
        List<Winner> winners = SkillWeekRules.winners(CompetitionTemplate.importedXp(progress), 10);
        assertEquals(winners.stream().map(w -> "u:" + w.userId()).toList(), own.stream().map(EventScoring.Standing::subject).toList());
        assertEquals(winners.stream().map(Winner::xp).toList(), own.stream().map(EventScoring.Standing::points).toList(), "a point is an XP");

        Event ordinary = new Event(2, GUILD, CompetitionTemplate.KEY, "Ordinary", "", Status.RUNNING, null, ScoringMode.INDIVIDUAL, START, END, 0,
                "{\"skillId\":9,\"xpSource\":\"plugin\"}", 1, START, null, false);
        assertEquals(null, new CompetitionTemplate().standings(ordinary, objectives, List.copyOf(progress), usual), "other competitions keep their points");
    }

    @Test
    void whoJoinsAfterTheWeekEndedIsntCounted() {
        fixture();
        long erin = Long.parseLong("6".repeat(18));
        List<Participant> players = new ArrayList<>(repository.participants(1));
        players.add(player(erin, Instant.parse("2026-11-02T00:05:00Z")));
        when(repository.participants(1)).thenReturn(players);
        when(hub.aliasOf(erin)).thenReturn(Optional.of("a1_alice")); // the ledger has gains for this alias in the week
        PluginXpTracker tracker = new PluginXpTracker(repository, events, hub).withClock(Clock.fixed(Instant.parse("2026-11-02T00:12:00Z"), ZoneOffset.UTC));
        assertTrue(tracker.pass(event).complete());
        assertFalse(CompetitionTemplate.importedXp(progress).containsKey(erin));
        verify(hub, never()).aliasOf(erin);
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoPassesAtOnceAcrossAMinuteNeverCountAGainTwice() throws Exception {
        fixture();
        java.util.concurrent.CountDownLatch inHub = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch letGo = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.stubbing.Answer<?> ledgerAnswer = i -> {
            Collection<String> aliases = i.getArgument(1);
            DataArray rows = DataArray.empty();
            ledger(aliases, i.getArgument(2), i.getArgument(3), i.getArgument(4)).forEach((alias, xp) -> rows.add(DataObject.empty().put("alias", alias).put("xp", xp)));
            return new RuneClansHubClient.Answer(200, DataObject.empty().put("players", rows));
        };
        Mockito.doAnswer(i -> {
            if (first.getAndSet(false)) {
                // the sweep's pass is at the hub when the closing pass starts, a minute later
                inHub.countDown();
                assertTrue(letGo.await(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            return ledgerAnswer.answer(i);
        }).when(hub).xpGains(eq(GUILD), any(Collection.class), any(), any(Instant.class), any(Instant.class));
        PluginXpTracker tracker = new PluginXpTracker(repository, events, hub).withClock(Clock.fixed(Instant.parse("2026-11-02T00:11:59Z"), ZoneOffset.UTC));
        Thread sweep = new Thread(() -> tracker.pass(event));
        sweep.start();
        assertTrue(inHub.await(10, java.util.concurrent.TimeUnit.SECONDS));
        tracker.withClock(Clock.fixed(Instant.parse("2026-11-02T00:12:00Z"), ZoneOffset.UTC));
        Thread closing = new Thread(() -> tracker.pass(event));
        closing.start();
        // the closing pass waits for the sweep's to finish rather than reading what is counted before it
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (closing.getState() != Thread.State.BLOCKED && closing.isAlive() && System.nanoTime() < deadline) Thread.onSpinWait();
        letGo.countDown();
        sweep.join(10_000);
        closing.join(10_000);
        assertEquals(Map.of(ALICE, 8_000L, BOB, 9_000L, CAROL, 7_000L, DAVE, 8_999L), CompetitionTemplate.importedXp(progress), "each gain counted once");
    }

    private static Participant player(long userId, Instant joined) {
        return new Participant(1, userId, GUILD, null, null, false, null, joined, null, null, null, null, null, null);
    }
}
