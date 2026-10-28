package com.runeclans.bot.events;

import com.runeclans.bot.events.BingoBoard.Board;
import com.runeclans.bot.events.EventBonuses.Bonus;
import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventScoring.Entry;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BingoTest {
    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    private static Board board(int size, long seed) {
        return BingoBoard.generate(size, seed, BingoBoard.defaultPool(), null, Set.of(), 50, 250);
    }

    @Test
    void aBoardHasTheRightNumberOfDifferentTilesAndBonuses() {
        Board b = board(5, 42);
        assertEquals(25, b.tiles().size());
        assertEquals(25, b.tiles().stream().map(BingoBoard.Tile::key).distinct().count());
        assertEquals(5 + 5 + 2 + 1, b.bonuses().size(), "rows, columns, two diagonals and the blackout");
        assertEquals(5, b.bonuses().stream().filter(x -> x.name().startsWith("Row")).findFirst().orElseThrow().keys().size());
        assertEquals(25, b.bonuses().get(b.bonuses().size() - 1).keys().size());
        assertEquals(250, b.bonuses().get(b.bonuses().size() - 1).points());
    }

    @Test
    void theSameSeedGivesTheSameBoardAndAnotherSeedADifferentOne() {
        assertEquals(board(5, 7).tiles(), board(5, 7).tiles());
        assertNotEquals(board(5, 7).tiles(), board(5, 8).tiles());
    }

    @Test
    void theDiagonalsRunCornerToCorner() {
        Board b = board(3, 1);
        List<String> keys = b.tiles().stream().map(BingoBoard.Tile::key).toList();
        Bonus down = b.bonuses().stream().filter(x -> x.name().equals("Diagonal down")).findFirst().orElseThrow();
        Bonus up = b.bonuses().stream().filter(x -> x.name().equals("Diagonal up")).findFirst().orElseThrow();
        assertEquals(List.of(keys.get(0), keys.get(4), keys.get(8)), down.keys());
        assertEquals(List.of(keys.get(2), keys.get(4), keys.get(6)), up.keys());
    }

    @Test
    void lockedTilesStayPutWhenTheRestAreRerolled() {
        Board first = board(5, 1);
        Set<String> locked = Set.of(first.tiles().get(0).key(), first.tiles().get(12).key(), first.tiles().get(24).key());
        Board second = BingoBoard.generate(5, 2, BingoBoard.defaultPool(), first.tiles(), locked, 50, 250);
        assertEquals(first.tiles().get(0), second.tiles().get(0));
        assertEquals(first.tiles().get(12), second.tiles().get(12));
        assertEquals(first.tiles().get(24), second.tiles().get(24));
        assertEquals(25, second.tiles().stream().map(BingoBoard.Tile::key).distinct().count(), "no tile appears twice");
        assertNotEquals(first.tiles().get(1), second.tiles().get(1));
    }

    @Test
    void aZeroBonusIsLeftOut() {
        Board b = BingoBoard.generate(3, 1, BingoBoard.defaultPool(), null, Set.of(), 0, 100);
        assertEquals(1, b.bonuses().size());
        assertEquals("Blackout", b.bonuses().get(0).name());
        assertTrue(BingoBoard.generate(3, 1, BingoBoard.defaultPool(), null, Set.of(), 0, 0).bonuses().isEmpty());
    }

    @Test
    void sizesAndPoolsAreChecked() {
        assertTrue(BingoBoard.problems(5, 60, 50, 250).isEmpty());
        assertFalse(BingoBoard.problems(2, 60, 50, 250).isEmpty());
        assertFalse(BingoBoard.problems(8, 100, 50, 250).isEmpty());
        assertFalse(BingoBoard.problems(7, 30, 50, 250).isEmpty(), "49 tiles don't come from a pool of 30");
        assertFalse(BingoBoard.problems(5, 60, -1, 250).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> BingoBoard.generate(9, 1, BingoBoard.defaultPool(), null, Set.of(), 50, 250));
        assertTrue(BingoBoard.defaultPool().size() >= 49, "the starter pool fills the largest board");
    }

    // ---------- bonuses in scoring ----------

    private static ObjectiveSpec obj(String key, long points) {
        return new ObjectiveSpec(key, key, "", points, Evidence.EITHER, 1, 1, List.of());
    }

    @Test
    void aBonusIsEarnedOnlyWhenEveryObjectiveInTheGroupIsDone() {
        var objectives = List.of(obj("a", 10), obj("b", 10), obj("c", 10));
        var bonuses = List.of(new Bonus("Row 1", List.of("a", "b"), 25));
        var partial = EventScoring.standings(objectives, List.of(new Entry("u:1", "a", 1, T0)), bonuses);
        assertEquals(10, partial.get(0).points());
        var full = EventScoring.standings(objectives, List.of(new Entry("u:1", "a", 1, T0), new Entry("u:1", "b", 1, T0.plusSeconds(5))), bonuses);
        assertEquals(10 + 10 + 25, full.get(0).points());
        var reversed = EventScoring.standings(objectives, List.of(new Entry("u:1", "a", 1, T0), new Entry("u:1", "b", 1, T0.plusSeconds(5)), new Entry("u:1", "b", -1, T0.plusSeconds(9))), bonuses);
        assertEquals(10, reversed.get(0).points(), "taking a tile back takes its bonus back");
    }

    @Test
    void bonusesAreReadAndChecked() {
        var config = DataObject.fromJson("{\"bonuses\":[{\"name\":\"Top row\",\"keys\":[\"a\",\"b\"],\"points\":30}]}");
        assertEquals(1, EventBonuses.parse(config).size());
        assertTrue(EventBonuses.problems(config, Set.of("a", "b")).isEmpty());
        assertFalse(EventBonuses.problems(config, Set.of("a")).isEmpty(), "b isn't an objective");
        assertFalse(EventBonuses.problems(DataObject.fromJson("{\"bonuses\":[{\"name\":\"\",\"keys\":[\"a\"],\"points\":1}]}"), Set.of("a")).isEmpty());
        assertFalse(EventBonuses.problems(DataObject.fromJson("{\"bonuses\":[{\"name\":\"X\",\"keys\":[],\"points\":1}]}"), Set.of("a")).isEmpty());
        assertFalse(EventBonuses.problems(DataObject.fromJson("{\"bonuses\":[{\"name\":\"X\",\"keys\":[\"a\"],\"points\":-5}]}"), Set.of("a")).isEmpty());
        assertTrue(EventBonuses.problems(DataObject.empty(), Set.of()).isEmpty());
        assertEquals(List.of(), EventBonuses.earned(EventBonuses.parse(config), Map.of("a", 1)));
        assertEquals(1, EventBonuses.earned(EventBonuses.parse(config), Map.of("a", 1, "b", 2)).size());
    }

    // ---------- the template ----------

    @Test
    void aNewBingoGetsABoardWhoseObjectivesMatchIt() {
        BingoTemplate template = new BingoTemplate();
        DataObject prepared = template.prepare(DataObject.fromJson("{\"board\":{\"size\":4,\"seed\":99}}"));
        List<ObjectiveSpec> starters = template.starterObjectives(prepared);
        assertEquals(16, starters.size());
        var keys = prepared.getObject("board").getArray("keys");
        assertEquals(16, keys.length());
        for (int i = 0; i < 16; i++) assertEquals(starters.get(i).key(), keys.getString(i), "board order matches the objectives");
        assertEquals(4 + 4 + 2 + 1, EventBonuses.parse(prepared).size());
        assertTrue(EventService.checkObjectives(starters).isEmpty());
        assertTrue(EventBonuses.problems(prepared, starters.stream().map(ObjectiveSpec::key).collect(Collectors.toSet())).isEmpty());
        assertTrue(template.configProblems(prepared).isEmpty());
    }

    @Test
    void theTemplateRejectsBadSettings() {
        BingoTemplate template = new BingoTemplate();
        assertFalse(template.configProblems(DataObject.fromJson("{\"board\":{\"size\":9}}")).isEmpty());
        assertFalse(template.configProblems(DataObject.fromJson("{\"lineBonus\":-1}")).isEmpty());
        assertTrue(template.configProblems(DataObject.empty()).isEmpty(), "a 5x5 board with the usual bonuses is the default");
    }

    @Test
    void rerollingKeepsLockedTilesAndAnyExtraObjectives() {
        BingoTemplate template = new BingoTemplate();
        DataObject prepared = template.prepare(DataObject.fromJson("{\"board\":{\"size\":3,\"seed\":5}}"));
        List<ObjectiveSpec> current = new java.util.ArrayList<>(template.starterObjectives(prepared));
        current.add(new ObjectiveSpec("side-quest", "Side quest", "", 5, Evidence.PROOF, 1, 1, List.of()));
        String lockedKey = current.get(4).key();
        var rerolled = template.reroll(prepared, current, null, 6L, Set.of(lockedKey));
        assertEquals(10, rerolled.objectives().size(), "nine tiles plus the side quest");
        assertEquals(lockedKey, rerolled.objectives().get(4).key());
        assertEquals("side-quest", rerolled.objectives().get(9).key());
        assertEquals(6L, rerolled.config().getObject("board").getLong("seed"));
        assertNotEquals(current.get(0).key(), rerolled.objectives().get(0).key());
        var bigger = template.reroll(prepared, current, 4, 1L, Set.of(lockedKey));
        assertEquals(17, bigger.objectives().size(), "a different size starts a fresh board and ignores locks");
    }
}
