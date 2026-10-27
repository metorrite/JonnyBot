package com.younglings.bot.events;

import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRepository.Participant;
import com.younglings.bot.events.EventRepository.Progress;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Source;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.SkillWeekRules.Winner;
import com.younglings.bot.runeclans.RuneClansHubClient;
import com.younglings.bot.runescape.PlayerLinkService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-541 acceptance: a week's winners are exactly the XP ledger's gains for that skill and span. The hub here answers {@code xp/gains} from a fixture
 * ledger (timed gains per player, skill and minute); the week's competition is fed through {@link PluginXpTracker} during the week and once more
 * at the close, and the winners {@link SkillWeekService#close} keeps must equal what the ledger says, with gains in other skills, before Monday
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
            new Gain("a1_dave", FLETCHING, Instant.parse("2026-10-28T08:00:00Z"), 8_999));

    private final EventRepository repository = Mockito.mock(EventRepository.class);
    private final EventService events = Mockito.mock(EventService.class);
    private final RuneClansHubClient hub = Mockito.mock(RuneClansHubClient.class);
    private final SkillWeekRepository weeks = Mockito.mock(SkillWeekRepository.class);
    private final List<Progress> progress = new ArrayList<>();

    /** What the ledger says each alias gained in {@code skill} from {@code from} to {@code to}. */
    private static Map<String, Long> ledger(Collection<String> aliases, int skill, Instant from, Instant to) {
        Map<String, Long> out = new HashMap<>();
        for (Gain g : LEDGER) {
            if (aliases.contains(g.alias()) && g.skill() == skill && !g.at().isBefore(from) && !g.at().isAfter(to)) out.merge(g.alias(), g.xp(), Long::sum);
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theWeeksWinnersAreTheLedgersGainsForThatSkillAndSpan() {
        Event event = new Event(1, GUILD, CompetitionTemplate.KEY, "Skill of the week", "", Status.RUNNING, null, ScoringMode.INDIVIDUAL, START, END, 0,
                "{\"skillId\":9,\"xpPerPoint\":100000,\"xpSource\":\"plugin\",\"wholeSpan\":true}", 1, START, null, false);
        when(repository.event(GUILD, 1)).thenReturn(Optional.of(event));
        when(repository.startedAt(1)).thenReturn(Optional.of(START.plusSeconds(300))); // the pass that opened it ran a few minutes in
        when(repository.participants(1)).thenReturn(List.of(player(ALICE, START), player(BOB, Instant.parse("2026-10-29T12:00:00Z")), player(CAROL, START), player(DAVE, START)));
        when(repository.progress(1)).thenAnswer(i -> List.copyOf(progress));
        for (long user : List.of(ALICE, BOB, CAROL, DAVE)) {
            String name = user == ALICE ? "alice" : user == BOB ? "bob" : user == CAROL ? "carol" : "dave";
            when(hub.aliasOf(user)).thenReturn(Optional.of("a1_" + name));
        }
        when(hub.xpGains(eq(GUILD), any(Collection.class), any(), any(Instant.class), any(Instant.class))).thenAnswer(i -> {
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

        PluginXpTracker tracker = new PluginXpTracker(repository, events, hub);
        // passes during the week, then the close a little after Sunday ends (when the next week's gains are already in the ledger)
        tracker.withClock(Clock.fixed(Instant.parse("2026-10-28T09:00:00Z"), ZoneOffset.UTC)).feed(event);
        tracker.withClock(Clock.fixed(Instant.parse("2026-10-31T19:00:00Z"), ZoneOffset.UTC)).feed(event);
        Instant closing = Instant.parse("2026-11-02T00:12:00Z");
        tracker.withClock(Clock.fixed(closing, ZoneOffset.UTC));
        SkillWeekService service = new SkillWeekService(weeks, events, repository, tracker, hub, Mockito.mock(PlayerLinkService.class)).withClock(Clock.fixed(closing, ZoneOffset.UTC));
        List<Winner> winners = service.close(new SkillWeekRepository.Week(GUILD, START, FLETCHING, 1L, null, null));

        Map<String, Long> truth = ledger(List.of("a1_alice", "a1_bob", "a1_carol", "a1_dave"), FLETCHING, START, END);
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
        verify(hub, Mockito.atLeastOnce()).xpGains(eq(GUILD), any(Collection.class), eq(FLETCHING), eq(START), eq(END));
        verify(events).moveStage(GUILD, 1, Status.SCORING);
        verify(events).moveStage(GUILD, 1, Status.DONE);
    }

    private static Participant player(long userId, Instant joined) {
        return new Participant(1, userId, GUILD, null, null, false, null, joined, null, null, null, null, null, null);
    }
}
