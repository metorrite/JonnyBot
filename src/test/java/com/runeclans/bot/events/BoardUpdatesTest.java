package com.runeclans.bot.events;

import com.runeclans.bot.events.BoardUpdates.Kind;
import com.runeclans.bot.events.BoardUpdates.Milestone;
import com.runeclans.bot.events.BoardUpdates.Mode;
import com.runeclans.bot.events.BoardUpdates.Settings;
import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventScoring.Entry;
import com.runeclans.bot.events.EventScoring.Standing;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The board update rules (T-650): reading them, when a held-back board is due, the rules in words, milestones and the daily recap. */
class BoardUpdatesTest {
    private static final Instant T = Instant.parse("2026-10-27T14:00:00Z");

    private static ObjectiveSpec tile(String key, String label) {
        return new ObjectiveSpec(key, label, "", 5, Evidence.EITHER, 1, 1, List.of());
    }

    private static final List<ObjectiveSpec> TILES = List.of(tile("a", "Nex: Torva boots"), tile("b", "Araxxor: Noxious blade"), tile("c", "Zamorak: Codex"), tile("d", "Kerapac: Staff"));
    // a 2x2 board: rows a-b and c-d, plus the blackout
    private static final List<EventBonuses.Bonus> BONUSES = List.of(new EventBonuses.Bonus("Row 1", List.of("a", "b"), 50), new EventBonuses.Bonus("Row 2", List.of("c", "d"), 50),
            new EventBonuses.Bonus("Blackout", List.of("a", "b", "c", "d"), 250));

    @Test
    void anEventThatNeverSavedRulesBehavesAsToday() {
        assertEquals(BoardUpdates.DEFAULTS, BoardUpdates.of(DataObject.fromJson("{}")));
        assertEquals(BoardUpdates.DEFAULTS, BoardUpdates.of(null));
        assertEquals(Mode.LIVE, BoardUpdates.DEFAULTS.mode());
        assertFalse(BoardUpdates.DEFAULTS.recap(), "the daily recap is off by default");
        assertFalse(BoardUpdates.DEFAULTS.milestones(), "every milestone is off by default");
        assertTrue(BoardUpdates.DEFAULTS.isDefault());
    }

    @Test
    void brokenSettingsFallBackToTheDefaults() {
        assertEquals(BoardUpdates.DEFAULTS, BoardUpdates.of(DataObject.fromJson("{\"updates\":\"nonsense\"}")));
        assertEquals(Mode.LIVE, BoardUpdates.of(DataObject.fromJson("{\"updates\":{\"mode\":\"SOMETIMES\"}}")).mode());
        assertEquals(BoardUpdates.MIN_EVERY_MINUTES, BoardUpdates.of(DataObject.fromJson("{\"updates\":{\"mode\":\"BATCHED\",\"everyMinutes\":1}}")).everyMinutes());
    }

    @Test
    void problemsAreSaidInWords() {
        assertEquals(List.of(), BoardUpdates.problems(DataObject.fromJson("{\"mode\":\"DAILY\",\"recap\":true}")));
        assertEquals(1, BoardUpdates.problems(DataObject.fromJson("{\"mode\":\"WEEKLY\"}")).size());
        assertEquals(1, BoardUpdates.problems(DataObject.fromJson("{\"mode\":\"BATCHED\",\"everyMinutes\":2}")).size());
        assertEquals(1, BoardUpdates.problems(DataObject.fromJson("{\"channelId\":\"general\"}")).size());
        assertEquals(1, BoardUpdates.problems(DataObject.fromJson("{\"recap\":\"yes\"}")).size());
    }

    @Test
    void aChannelIdMustFitALong() {
        String big = "9".repeat(19); // above Long.MAX_VALUE
        String twenty = "1".repeat(20);
        assertEquals(1, BoardUpdates.problems(DataObject.fromJson("{\"channelId\":\"" + big + "\"}")).size());
        assertEquals(1, BoardUpdates.problems(DataObject.fromJson("{\"channelId\":\"" + twenty + "\"}")).size());
        assertNull(BoardUpdates.read(DataObject.fromJson("{\"channelId\":\"" + big + "\"}")).channelId(), "read never throws on an id that doesn't fit");
        assertNull(BoardUpdates.read(DataObject.fromJson("{\"channelId\":\"" + twenty + "\"}")).channelId());
        String fits = "1".repeat(19);
        assertEquals(List.of(), BoardUpdates.problems(DataObject.fromJson("{\"channelId\":\"" + fits + "\"}")));
        assertEquals(Long.parseLong(fits), BoardUpdates.read(DataObject.fromJson("{\"channelId\":\"" + fits + "\"}")).channelId());
    }

    @Test
    void aSettingsSaveKeepsTheUpdateRules() {
        // the dashboard's Setup card sends its own copy of the config, which may not have the rules saved on the Updates tab since
        DataObject before = DataObject.fromJson("{\"board\":{\"size\":5},\"updates\":{\"mode\":\"DAILY\",\"recap\":true}}");
        DataObject stale = DataObject.fromJson("{\"board\":{\"size\":5},\"description\":\"x\"}");
        EventService.keepUpdates(before, stale);
        assertEquals(Mode.DAILY, BoardUpdates.of(stale).mode());
        assertTrue(BoardUpdates.of(stale).recap());
        // and a settings save can't sneak rules in past the Updates route's checks
        DataObject sneaky = DataObject.fromJson("{\"updates\":{\"mode\":\"BATCHED\"}}");
        EventService.keepUpdates(DataObject.fromJson("{}"), sneaky);
        assertFalse(sneaky.hasKey("updates"));
    }

    @Test
    void hiddenStandingsAreSaidInThePlainWords() {
        Settings all = new Settings(Mode.LIVE, 30, true, true, false, false, null);
        assertEquals(BoardUpdates.describe(all), BoardUpdates.describe(all, false));
        List<String> hidden = BoardUpdates.describe(all, true);
        assertTrue(hidden.stream().anyMatch(l -> l.contains("no milestones are announced")), hidden.toString());
        assertTrue(hidden.stream().anyMatch(l -> l.contains("without saying who finished them")), hidden.toString());
        assertEquals(BoardUpdates.describe(BoardUpdates.DEFAULTS), BoardUpdates.describe(BoardUpdates.DEFAULTS, true), "nothing to add when nothing is posted");
        String recap = BoardUpdates.previews("B", all, List.of(), T, "The standings are only shown to staff.").get("recap");
        assertTrue(recap.contains("The standings are only shown to staff.") && !recap.contains("Team Zaros"), recap);
    }

    @Test
    void savingTheDefaultsTakesTheRulesOutAgain() {
        DataObject config = DataObject.fromJson("{\"board\":{\"size\":5}}");
        Settings daily = new Settings(Mode.DAILY, 30, true, false, true, false, null);
        DataObject saved = BoardUpdates.withSettings(config, daily);
        assertEquals(daily, BoardUpdates.of(saved));
        assertFalse(config.hasKey("updates"), "the config passed in is left alone");
        DataObject back = BoardUpdates.withSettings(saved, BoardUpdates.DEFAULTS);
        assertFalse(back.hasKey("updates"));
        assertEquals(5, back.getObject("board").getInt("size"));
    }

    @Test
    void theDailyResetIsMidnightUtc() {
        assertEquals(Instant.parse("2026-10-27T00:00:00Z"), BoardUpdates.latestReset(T));
        assertEquals(Instant.parse("2026-10-28T00:00:00Z"), BoardUpdates.nextReset(T));
    }

    @Test
    void aLiveBoardIsAlwaysDue() {
        assertTrue(BoardUpdates.due(BoardUpdates.DEFAULTS, T, T, T));
    }

    @Test
    void aBatchedBoardIsDrawnAtMostOnceEveryNMinutes() {
        Settings every30 = new Settings(Mode.BATCHED, 30, false, false, false, false, null);
        assertTrue(BoardUpdates.due(every30, T, null, T), "never drawn since the bot started: straight away");
        assertFalse(BoardUpdates.due(every30, T, T, T.plus(Duration.ofMinutes(29))));
        assertTrue(BoardUpdates.due(every30, T, T, T.plus(Duration.ofMinutes(30))));
        assertEquals(T.plus(Duration.ofMinutes(30)), BoardUpdates.dueAt(every30, T.plusSeconds(60), T));
    }

    @Test
    void aDailyBoardWaitsForTheReset() {
        Settings daily = new Settings(Mode.DAILY, 30, false, false, false, false, null);
        assertFalse(BoardUpdates.due(daily, T, null, Instant.parse("2026-10-27T23:59:00Z")));
        assertTrue(BoardUpdates.due(daily, T, null, Instant.parse("2026-10-28T00:00:30Z")));
        assertEquals(Instant.parse("2026-10-28T00:00:00Z"), BoardUpdates.dueAt(daily, T, null));
    }

    @Test
    void theRulesReadAsPlainSentences() {
        assertEquals(List.of("The board updates a few seconds after each approved tile."), BoardUpdates.describe(BoardUpdates.DEFAULTS));
        List<String> all = BoardUpdates.describe(new Settings(Mode.BATCHED, 60, true, true, true, true, null));
        assertEquals(5, all.size());
        assertEquals("The board updates at most once every hour, with every tile approved since.", all.get(0));
        assertTrue(BoardUpdates.describe(new Settings(Mode.DAILY, 30, false, false, false, false, null)).get(0).contains("00:00 UTC"));
    }

    @Test
    void milestonesAreFoundInTheOrderTheyHappened() {
        List<Entry> log = List.of(
                new Entry("t:1", "a", 1, T),
                new Entry("t:2", "a", 1, T.plusSeconds(10)), // the second team on a tile is no news
                new Entry("t:2", "c", 1, T.plusSeconds(20)),
                new Entry("t:1", "b", 1, T.plusSeconds(30)), // team 1 finishes row 1
                new Entry("t:2", "d", 1, T.plusSeconds(40)), // team 2 finishes row 2: not the first line
                new Entry("t:1", "c", 1, T.plusSeconds(50)),
                new Entry("t:1", "d", 1, T.plusSeconds(60))); // team 1 blackout
        List<Milestone> found = BoardUpdates.milestones(TILES, BONUSES, log);
        assertEquals(List.of("tile:a", "tile:c", "tile:b", "line", "tile:d", "blackout"), found.stream().map(Milestone::key).toList());
        Milestone line = found.stream().filter(m -> m.kind() == Kind.FIRST_LINE).findFirst().orElseThrow();
        assertEquals("t:1", line.subject());
        assertEquals("Row 1", line.detail());
        assertEquals("t:1", found.get(found.size() - 1).subject());
    }

    @Test
    void aTileTakenBackDoesNotCountAsFinished() {
        List<Entry> log = List.of(new Entry("u:7", "a", 1, T), new Entry("u:7", "a", -1, T.plusSeconds(5)), new Entry("u:8", "a", 1, T.plusSeconds(9)));
        List<Milestone> found = BoardUpdates.milestones(TILES, BONUSES, log);
        assertEquals(1, found.size());
        assertEquals("u:7", found.get(0).subject(), "the first to finish it is still the first, even if it was taken back later");
    }

    @Test
    void onlySwitchedOnAndFreshMilestonesAreAnnounced() {
        List<Milestone> all = List.of(new Milestone("tile:a", Kind.FIRST_TILE, "t:1", "A", T.minus(Duration.ofHours(7))),
                new Milestone("tile:b", Kind.FIRST_TILE, "t:1", "B", T.minus(Duration.ofMinutes(1))),
                new Milestone("line", Kind.FIRST_LINE, "t:1", "Row 1", T.minus(Duration.ofMinutes(1))));
        assertEquals(List.of(), BoardUpdates.toAnnounce(BoardUpdates.DEFAULTS, all, T));
        Settings tiles = new Settings(Mode.LIVE, 30, false, false, false, true, null);
        assertEquals(List.of("tile:b"), BoardUpdates.toAnnounce(tiles, all, T).stream().map(Milestone::key).toList(), "a 7-hour-old tile is old news");
        Settings lines = new Settings(Mode.LIVE, 30, false, true, false, false, null);
        assertEquals(List.of("line"), BoardUpdates.toAnnounce(lines, all, T).stream().map(Milestone::key).toList());
    }

    @Test
    void milestonePostsNameTheTeamWithoutMentions() {
        String text = BoardUpdates.milestoneText("Bingo night", new Milestone("line", Kind.FIRST_LINE, "t:1", "Row 2", T), "Team Zaros");
        assertTrue(text.contains("**Team Zaros** is the first to finish a line (Row 2)."), text);
        assertFalse(text.contains("<@"));
        assertTrue(BoardUpdates.milestoneText("B", new Milestone("blackout", Kind.FIRST_BLACKOUT, "t:1", "", T), "X").contains("every tile"));
        assertTrue(BoardUpdates.milestoneText("B", new Milestone("tile:a", Kind.FIRST_TILE, "t:1", "Nex: Torva boots", T), "X").contains("**Nex: Torva boots**"));
    }

    @Test
    void theRecapListsYesterdaysTilesAndTheTopThree() {
        Instant reset = Instant.parse("2026-10-27T00:00:00Z");
        List<Entry> log = List.of(new Entry("t:1", "a", 1, reset.minus(Duration.ofHours(30))), // the day before yesterday
                new Entry("t:1", "b", 1, reset.minus(Duration.ofHours(3))),
                new Entry("t:2", "a", 1, reset.minus(Duration.ofHours(1))),
                new Entry("t:2", "b", 1, reset.plusSeconds(1))); // today
        List<BoardUpdates.Finished> finished = BoardUpdates.finishedBetween(TILES, log, reset.minus(Duration.ofDays(1)), reset);
        assertEquals(List.of("b", "a"), finished.stream().map(BoardUpdates.Finished::objectiveKey).toList());
        List<Standing> standings = List.of(new Standing("t:1", 60, 2, Map.of(), null), new Standing("t:2", 10, 1, Map.of(), null), new Standing("t:3", 5, 1, Map.of(), null),
                new Standing("t:4", 1, 1, Map.of(), null));
        Map<String, String> names = Map.of("t:1", "Zaros", "t:2", "Seren", "t:3", "Guthix", "t:4", "Bandos");
        String text = BoardUpdates.recapText("Bingo night", reset, finished, standings, names::get, true, "");
        assertTrue(text.startsWith("### Bingo night · Daily recap, 26 Oct"), text);
        assertTrue(text.contains("**2 tiles finished**"));
        assertTrue(text.contains("- Araxxor: Noxious blade · Zaros"));
        assertTrue(text.contains("3. **Guthix** · 5 pts"));
        assertFalse(text.contains("Bandos"), "only the top 3");
        assertEquals("recap:2026-10-26", BoardUpdates.recapKey(reset));
    }

    @Test
    void theRecapKeepsHiddenStandingsHiddenAndAQuietDayPostsNothing() {
        Instant reset = Instant.parse("2026-10-27T00:00:00Z");
        List<BoardUpdates.Finished> one = List.of(new BoardUpdates.Finished("a", "Nex", "u:1", reset.minusSeconds(60)));
        String hidden = BoardUpdates.recapText("B", reset, one, List.of(new Standing("u:1", 5, 1, Map.of(), null)), s -> "Ann", false, "The standings are only shown to staff.");
        assertTrue(hidden.contains("The standings are only shown to staff."));
        assertFalse(hidden.contains("5 pts"));
        assertTrue(hidden.contains("- Nex"), "the tiles are still listed");
        assertFalse(hidden.contains("Ann"), "but not who finished them, which would give the standings away");
        String shown = BoardUpdates.recapText("B", reset, one, List.of(), s -> "Ann", true, "");
        assertTrue(shown.contains("- Nex · Ann"));
        assertNull(BoardUpdates.recapText("B", reset, List.of(), List.of(), s -> "x", true, ""));
    }

    @Test
    void aLongRecapStaysInsideOneMessage() {
        Instant reset = Instant.parse("2026-10-27T00:00:00Z");
        List<BoardUpdates.Finished> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(new BoardUpdates.Finished("k" + i, "A very long tile name number " + i + " ".repeat(60), "u:1", reset.minusSeconds(60)));
        String text = BoardUpdates.recapText("B", reset, many, List.of(), s -> "Ann", true, "");
        assertTrue(text.length() <= BoardUpdates.MAX_POST);
    }

    @Test
    void everyPostHasAPreview() {
        Map<String, String> previews = BoardUpdates.previews("Bingo night", BoardUpdates.DEFAULTS, TILES, T);
        assertEquals(List.of("board", "recap", "firstLine", "firstBlackout", "firstTile"), List.copyOf(previews.keySet()));
        assertTrue(previews.get("firstTile").contains("Nex: Torva boots"), "a preview uses the event's own tiles");
        previews.values().forEach(text -> assertFalse(text.contains("@"), text));
    }
}
