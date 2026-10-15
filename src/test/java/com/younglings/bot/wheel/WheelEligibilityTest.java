package com.younglings.bot.wheel;

import com.younglings.bot.wheel.WheelEligibility.Link;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelEligibilityTest {
    private static final List<String> RANKS = List.of("Recruit", "Corporal", "Sergeant", "Lieutenant", "Captain", "General", "Admin", "Organiser", "Coordinator", "Overseer", "Deputy Owner", "Owner");
    private static final Predicate<String> ADMIN_PLUS = WheelEligibility.atLeast(RANKS, "Admin");
    private static final LongPredicate NOBODY = id -> false;

    private static Map<String, String> ranks(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i].toLowerCase(), pairs[i + 1]);
        return map;
    }

    @Test
    void anAdminAndTheirLinkedAccountsAreAllLeftOff() {
        // Metorrite is an Admin in the clan and has two more accounts linked through /rs; all three capped
        var links = List.of(new Link("Metorrite", 1), new Link("MetorLuvsJon", 1), new Link("Metorrite Alt", 1), new Link("Axley", 2));
        var result = WheelEligibility.select(List.of("Axley", "Metorrite", "MetorLuvsJon", "Metorrite Alt"),
                ranks("Metorrite", "Admin", "MetorLuvsJon", "Recruit", "Metorrite Alt", "Recruit", "Axley", "General"), ADMIN_PLUS, links, NOBODY);
        assertEquals(List.of("Axley"), result.names());
        assertEquals(1, result.leftOut().size());
        assertEquals("Metorrite + MetorLuvsJon + Metorrite Alt", result.leftOut().get(0).name());
        assertEquals("clan rank Admin", result.leftOut().get(0).reason());
    }

    @Test
    void anAccountLinkedToAnAdminIsLeftOffEvenIfItsOwnRankIsLow() {
        var links = List.of(new Link("Boss Main", 7), new Link("Boss Alt", 7));
        // only the alt capped; its owner's main account is an Overseer
        var result = WheelEligibility.select(List.of("Boss Alt"), ranks("Boss Main", "Overseer", "Boss Alt", "Recruit"), ADMIN_PLUS, links, NOBODY);
        assertTrue(result.names().isEmpty());
        assertEquals("linked to Boss Main (clan rank Overseer)", result.leftOut().get(0).reason());
    }

    @Test
    void aDiscordAdminIsLeftOffWhateverTheirClanRank() {
        var links = List.of(new Link("Mod Main", 5), new Link("Mod Alt", 5));
        var result = WheelEligibility.select(List.of("Mod Main", "Mod Alt", "Regular"),
                ranks("Mod Main", "Recruit", "Mod Alt", "Recruit", "Regular", "Recruit"), ADMIN_PLUS, links, id -> id == 5);
        assertEquals(List.of("Regular"), result.names());
        assertEquals("Discord admin", result.leftOut().get(0).reason());
    }

    @Test
    void aClanAdminWithNoLinkedAccountIsLeftOffByRank() {
        var result = WheelEligibility.select(List.of("Lonely Admin", "Someone"), ranks("Lonely Admin", "Deputy Owner", "Someone", "Captain"), ADMIN_PLUS, List.of(), NOBODY);
        assertEquals(List.of("Someone"), result.names());
        assertEquals("clan rank Deputy Owner", result.leftOut().get(0).reason());
    }

    @Test
    void aPersonWhoCappedOnTwoAccountsHasOneEntry() {
        var links = List.of(new Link("Jonny Yong", 9), new Link("Jonny Young", 9));
        var result = WheelEligibility.select(List.of("Telnos", "Jonny Yong", "Jonny Young"),
                ranks("Jonny Yong", "Captain", "Jonny Young", "Recruit", "Telnos", "General"), ADMIN_PLUS, links, NOBODY);
        assertEquals(List.of("Telnos", "Jonny Yong"), result.names(), "one entry, in the order they first appear");
        assertEquals(1, result.merged().size());
        assertEquals(List.of("Jonny Yong", "Jonny Young"), result.merged().get(0).accounts());
        assertTrue(result.leftOut().isEmpty());
    }

    @Test
    void generalIsNotAdminAndUnlinkedPeopleCountOnTheirOwn() {
        var result = WheelEligibility.select(List.of("A", "B", "C"), ranks("A", "General", "B", "Captain", "C", "Admin"), ADMIN_PLUS, List.of(), NOBODY);
        assertEquals(List.of("A", "B"), result.names());
        assertEquals("C", result.leftOut().get(0).name());
    }

    @Test
    void theRanksAreMatchedIgnoringCaseAndAnUnknownRankIsNotAdmin() {
        var admin = WheelEligibility.atLeast(RANKS, "admin");
        assertTrue(admin.test("ADMIN"));
        assertTrue(admin.test("owner"));
        assertFalse(admin.test("General"));
        assertFalse(admin.test("Some New Rank"));
        // a server whose rank list has no "Admin" falls back to the standard ranks
        assertTrue(WheelEligibility.atLeast(List.of(), "Admin").test("Coordinator"));
    }

    @Test
    void nobodyCappedMeansAnEmptyWheel() {
        var result = WheelEligibility.select(List.of(), Map.of(), ADMIN_PLUS, List.of(), NOBODY);
        assertTrue(result.names().isEmpty() && result.leftOut().isEmpty() && result.merged().isEmpty());
    }
}
