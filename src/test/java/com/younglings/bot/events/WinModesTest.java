package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventScoring.Entry;
import com.younglings.bot.events.WinModes.Entrant;
import com.younglings.bot.events.WinModes.Entries;
import com.younglings.bot.events.WinModes.Mode;
import com.younglings.bot.events.WinModes.Round;
import com.younglings.bot.events.WinModes.Settings;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every way to win (T-649): points as today, the bot's prize draw, first to a line and first to a blackout. Pure. */
class WinModesTest {
    private static final Instant T = Instant.parse("2026-12-01T12:00:00Z");
    private static final List<ObjectiveSpec> TILES = List.of(tile("a"), tile("b"), tile("c"), tile("d"));
    private static final List<EventBonuses.Bonus> LINES = List.of(new EventBonuses.Bonus("Row 1", List.of("a", "b"), 50), new EventBonuses.Bonus("Row 2", List.of("c", "d"), 50),
            new EventBonuses.Bonus("Blackout", List.of("a", "b", "c", "d"), 250));
    private static final Settings DRAW = new Settings(Mode.DRAW, Entries.PER_TILE, 1, 1, true);

    private static ObjectiveSpec tile(String key) {
        return new ObjectiveSpec(key, "Tile " + key, "", 10, Evidence.EITHER, 1, 1, List.of());
    }

    private static Entry done(String subject, String tile, int minutes) {
        return new Entry(subject, tile, 1, T.plus(Duration.ofMinutes(minutes)));
    }

    // ---------- settings ----------

    @Test
    void anEventThatNeverSavedWinRulesIsWonOnPointsAsToday() {
        assertEquals(WinModes.DEFAULTS, WinModes.of(DataObject.fromJson("{}")));
        assertEquals(Mode.POINTS, WinModes.of(null).mode());
        assertEquals(WinModes.DEFAULTS, WinModes.of(DataObject.fromJson("{\"win\":\"broken\"}")), "a broken setting is the default, never a surprise winner");
        assertTrue(WinModes.DEFAULTS.isDefault());
        assertEquals(List.of("Most points wins: the standings decide the places."), WinModes.describe(WinModes.DEFAULTS));
    }

    @Test
    void rulesAreSavedInTheConfigAndTheDefaultsTakeThemOutAgain() {
        DataObject config = DataObject.fromJson("{\"board\":{\"size\":5}}");
        Settings s = new Settings(Mode.DRAW, Entries.PER_PLAYER, 3, 2, false);
        DataObject saved = WinModes.withSettings(config, s);
        assertEquals(s, WinModes.of(saved));
        assertEquals(5, saved.getObject("board").getInt("size"));
        assertFalse(config.hasKey("win"), "the original is not changed");
        assertFalse(WinModes.withSettings(saved, WinModes.DEFAULTS).hasKey("win"));
    }

    @Test
    void whatCannotBeSavedIsRefusedWithReasons() {
        assertTrue(WinModes.problems(DataObject.fromJson("{\"mode\":\"DRAW\",\"minTiles\":3,\"winners\":2,\"entries\":\"PER_PLAYER\"}"), false).isEmpty());
        assertFalse(WinModes.problems(DataObject.fromJson("{\"mode\":\"LOTTERY\"}"), true).isEmpty());
        assertFalse(WinModes.problems(DataObject.fromJson("{\"mode\":\"FIRST_LINE\"}"), false).isEmpty(), "lines need a bingo board");
        assertTrue(WinModes.problems(DataObject.fromJson("{\"mode\":\"FIRST_BLACKOUT\"}"), true).isEmpty());
        assertFalse(WinModes.problems(DataObject.fromJson("{\"mode\":\"DRAW\",\"minTiles\":0}"), true).isEmpty());
        assertFalse(WinModes.problems(DataObject.fromJson("{\"mode\":\"DRAW\",\"winners\":99}"), true).isEmpty());
        assertFalse(WinModes.problems(DataObject.fromJson("{\"entries\":\"PER_TEAM_MEMBER\"}"), true).isEmpty());
        assertFalse(WinModes.problems(DataObject.fromJson("{\"autoDraw\":\"yes\"}"), true).isEmpty());
    }

    @Test
    void everyModeIsDescribedInPlainWords() {
        assertEquals(4, WinModes.describe(DRAW).size());
        assertTrue(WinModes.describe(DRAW).get(1).contains("1 tile"));
        assertTrue(WinModes.describe(new Settings(Mode.DRAW, Entries.PER_PLAYER, 5, 3, false)).get(0).contains("3 winners"));
        assertTrue(WinModes.describe(new Settings(Mode.FIRST_LINE, Entries.PER_TILE, 1, 1, true)).get(0).contains("line"));
        assertTrue(WinModes.describe(new Settings(Mode.FIRST_BLACKOUT, Entries.PER_TILE, 1, 1, true)).get(0).contains("every tile"));
    }

    // ---------- who qualifies ----------

    @Test
    void oneEntryPerFinishedTileByDefaultInAFixedOrder() {
        List<Entry> log = List.of(done("u:2", "a", 1), done("u:2", "b", 2), done("u:1", "a", 3), done("u:3", "x", 4));
        List<Entrant> entrants = WinModes.entrants(DRAW, TILES, log, null, s -> true);
        assertEquals(List.of(new Entrant("u:1", 1, 1), new Entrant("u:2", 2, 2)), entrants, "sorted by subject; a removed tile never counts");
    }

    @Test
    void organisersCanGiveOneEntryEachAndRaiseTheMinimum() {
        List<Entry> log = List.of(done("u:2", "a", 1), done("u:2", "b", 2), done("u:2", "c", 2), done("u:1", "a", 3), done("u:1", "b", 3));
        assertEquals(List.of(new Entrant("u:1", 2, 1), new Entrant("u:2", 3, 1)), WinModes.entrants(new Settings(Mode.DRAW, Entries.PER_PLAYER, 1, 1, true), TILES, log, null, s -> true));
        assertEquals(List.of(new Entrant("u:2", 3, 3)), WinModes.entrants(new Settings(Mode.DRAW, Entries.PER_TILE, 3, 1, true), TILES, log, null, s -> true));
    }

    @Test
    void onlyTilesFinishedByTheEndTimeAndStillFinishedCountAndRemovedPlayersAreLeftOut() {
        List<Entry> log = new ArrayList<>(List.of(done("u:1", "a", 1), done("u:1", "b", 90), done("u:2", "a", 1), done("u:3", "a", 1)));
        log.add(new Entry("u:3", "a", -1, T.plus(Duration.ofMinutes(5)))); // taken back
        List<Entrant> entrants = WinModes.entrants(DRAW, TILES, log, T.plus(Duration.ofMinutes(60)), s -> !s.equals("u:2"));
        assertEquals(List.of(new Entrant("u:1", 1, 1)), entrants);
    }

    // ---------- the draw ----------

    @Test
    void theSameSeedAndEntriesAlwaysGiveTheSameWinnersSoAnyoneCanCheck() {
        List<Entrant> entrants = List.of(new Entrant("u:1", 1, 1), new Entrant("u:2", 5, 5), new Entrant("u:3", 2, 2));
        for (long seed : new long[] {1, 42, -7, Long.MAX_VALUE}) {
            assertEquals(WinModes.draw(entrants, 3, Set.of(), seed), WinModes.draw(entrants, 3, Set.of(), seed));
        }
    }

    @Test
    void nobodyWinsTwiceAndTheDrawStopsWhenEveryoneHasWon() {
        List<Entrant> entrants = List.of(new Entrant("u:1", 1, 1), new Entrant("u:2", 5, 5));
        List<String> winners = WinModes.draw(entrants, 5, Set.of(), 9);
        assertEquals(2, winners.size());
        assertEquals(Set.of("u:1", "u:2"), Set.copyOf(winners));
        assertEquals(List.of("u:1"), WinModes.draw(entrants, 1, Set.of("u:2"), 9), "an excluded subject is never drawn");
        assertTrue(WinModes.draw(List.of(), 1, Set.of(), 9).isEmpty(), "no qualifiers, no winner");
    }

    @Test
    void moreEntriesMeanBetterOdds() {
        List<Entrant> entrants = List.of(new Entrant("u:1", 1, 1), new Entrant("u:2", 9, 9));
        Map<String, Integer> wins = new HashMap<>();
        for (long seed = 0; seed < 2000; seed++) wins.merge(WinModes.draw(entrants, 1, Set.of(), seed).get(0), 1, Integer::sum);
        assertTrue(wins.get("u:2") > 1600 && wins.get("u:2") < 1990, "about 90% for nine entries out of ten: " + wins);
        assertTrue(wins.get("u:1") > 0);
    }

    @Test
    void aRerollFillsThePlaceOfAWinnerWhoDidNotClaimWithSomeoneNotDrawnBefore() {
        List<Entrant> entrants = List.of(new Entrant("u:1", 1, 1), new Entrant("u:2", 1, 1), new Entrant("u:3", 1, 1));
        Round first = new Round(0, 5, entrants, List.of("u:2", "u:1"), null, null, T, null);
        assertEquals(List.of("u:2", "u:1"), WinModes.winners(List.of(first)));
        assertEquals(Set.of("u:1", "u:2"), WinModes.alreadyDrawn(List.of(first)));
        List<String> again = WinModes.draw(entrants, 1, WinModes.alreadyDrawn(List.of(first)), 77);
        assertEquals(List.of("u:3"), again);
        Round reroll = new Round(1, 77, entrants, again, 1, "u:2", T, 9L);
        assertTrue(reroll.reroll());
        assertEquals(List.of("u:3", "u:1"), WinModes.winners(List.of(first, reroll)));
        assertEquals(Set.of("u:1", "u:2", "u:3"), WinModes.alreadyDrawn(List.of(first, reroll)), "the one who didn't claim can't be drawn again");
        Round empty = new Round(2, 78, entrants, List.of(), 2, "u:1", T, 9L);
        assertNull(WinModes.winners(List.of(first, reroll, empty)).get(1), "nobody left: the place stays empty");
    }

    @Test
    void staffCanOnlyDrawAgainWhenTheLastDrawFoundNobody() {
        assertTrue(WinModes.mayDraw(List.of()));
        assertTrue(WinModes.mayDraw(List.of(new Round(0, 1, List.of(), List.of(), null, null, T, null))));
        assertFalse(WinModes.mayDraw(List.of(new Round(0, 1, List.of(new Entrant("u:1", 1, 1)), List.of("u:1"), null, null, T, null))));
    }

    @Test
    void theBotDrawsByItselfOnlyOnceAtTheEndTimeAndNeverLongAfter() {
        Instant end = T;
        assertFalse(WinModes.autoDue(DRAW, end, List.of(), end.minusSeconds(1)), "not before the end");
        assertTrue(WinModes.autoDue(DRAW, end, List.of(), end));
        assertTrue(WinModes.autoDue(DRAW, end, List.of(), end.plus(Duration.ofHours(30))), "a bot that was down catches up");
        assertFalse(WinModes.autoDue(DRAW, end, List.of(), end.plus(WinModes.AUTO_WINDOW)), "never days later");
        assertFalse(WinModes.autoDue(DRAW, end, List.of(new Round(0, 1, List.of(), List.of(), null, null, T, null)), end.plusSeconds(60)), "only once");
        assertFalse(WinModes.autoDue(new Settings(Mode.DRAW, Entries.PER_TILE, 1, 1, false), end, List.of(), end), "staff draw by hand");
        assertFalse(WinModes.autoDue(WinModes.DEFAULTS, end, List.of(), end), "a points event never draws");
        assertFalse(WinModes.autoDue(DRAW, null, List.of(), end), "no end time, no automatic draw");
    }

    @Test
    void theAnnouncementNamesTheWinnersShowsTheSeedAndNeverMentionsAnyone() {
        List<Entrant> entrants = List.of(new Entrant("u:1", 2, 2), new Entrant("t:5", 3, 3));
        Round r = new Round(0, -5, entrants, List.of("t:5", "u:1"), null, null, T, null);
        String text = WinModes.drawText("Advent bingo", DRAW, r, s -> s.equals("t:5") ? "Team Zaros" : "Seren");
        assertTrue(text.contains("1st. **Team Zaros**"));
        assertTrue(text.contains("2nd. **Seren**"));
        assertTrue(text.contains("5 entries from 2 qualifiers"));
        assertTrue(text.contains(Long.toUnsignedString(-5)), "the seed is shown");
        assertFalse(text.contains("<@"));
        assertTrue(WinModes.drawText("Advent", DRAW, new Round(0, 1, List.of(), List.of(), null, null, T, null), s -> s).contains("Nobody qualified"));
        String reroll = WinModes.rerollText("Advent", new Round(1, 3, entrants, List.of("u:1"), 1, "t:5", T, 9L), s -> s.equals("t:5") ? "Team Zaros" : "Seren");
        assertTrue(reroll.contains("**Team Zaros** didn't claim 1st place"));
        assertTrue(reroll.contains("**Seren** wins"));
        assertEquals("11th", WinModes.ordinal(11));
        assertEquals("22nd", WinModes.ordinal(22));
        assertEquals("103rd", WinModes.ordinal(103));
    }

    // ---------- first to a line or the board ----------

    @Test
    void firstToALineGivesThePlacesInTheOrderLinesWereFinished() {
        List<Entry> log = List.of(done("u:1", "a", 1), done("u:2", "c", 2), done("u:2", "d", 3), done("u:1", "b", 4), done("u:3", "a", 5));
        assertEquals(List.of("u:2", "u:1"), WinModes.finishOrder(Mode.FIRST_LINE, TILES, LINES, log), "u:3 never finished a line");
    }

    @Test
    void firstToABlackoutNeedsEveryTile() {
        List<Entry> log = List.of(done("u:1", "a", 1), done("u:1", "b", 2), done("u:2", "a", 3), done("u:2", "b", 4), done("u:2", "c", 5), done("u:2", "d", 6),
                done("u:1", "c", 7), done("u:1", "d", 8));
        assertEquals(List.of("u:2", "u:1"), WinModes.finishOrder(Mode.FIRST_BLACKOUT, TILES, LINES, log));
    }

    @Test
    void aLineUndoneByATakenBackEntryOnlyCountsFromWhenItIsFinishedAgain() {
        List<Entry> log = new ArrayList<>(List.of(done("u:1", "a", 1), done("u:1", "b", 2), done("u:2", "c", 3), done("u:2", "d", 4)));
        log.add(new Entry("u:1", "b", -1, T.plus(Duration.ofMinutes(5))));
        log.add(done("u:1", "b", 6));
        assertEquals(List.of("u:2", "u:1"), WinModes.finishOrder(Mode.FIRST_LINE, TILES, LINES, log));
        assertNotEquals(List.of(), WinModes.finishOrder(Mode.FIRST_LINE, TILES, LINES, log));
        assertTrue(WinModes.finishOrder(Mode.POINTS, TILES, LINES, log).isEmpty(), "points and draws use other rules");
        assertTrue(WinModes.finishOrder(Mode.FIRST_LINE, List.of(), LINES, log).isEmpty());
    }
}
