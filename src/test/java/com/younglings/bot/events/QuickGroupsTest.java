package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.QuickGroupRepository.Group;
import com.younglings.bot.events.QuickGroupRepository.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The group finder's rules (T-589): what a new group may be, how its slots are kept and counted, and its Discord post. */
class QuickGroupsTest {
    private static final Instant NOW = Instant.parse("2026-10-27T18:00:00Z");

    private static DataObject body(String activity, String startsAt, DataArray slots) {
        DataObject b = DataObject.empty().put("activity", activity).put("startsAt", startsAt);
        if (slots != null) b.put("slots", slots);
        return b;
    }

    private static DataArray slots(Object... roleThenPlaces) {
        DataArray a = DataArray.empty();
        for (int i = 0; i < roleThenPlaces.length; i += 2)
            a.add(DataObject.empty().put("role", roleThenPlaces[i]).put("places", roleThenPlaces[i + 1]));
        return a;
    }

    @Test
    void aGroupWithRoleSlotsIsCheckedAndCleaned() {
        QuickGroups.Plan plan = QuickGroups.check(body("  Solak ​ ", "2026-10-27T20:00:00Z", slots("Base", 1, "DPS", 3))
                .put("note", "Bring\nyour own food").put("link", "https://younglings.runeclans.com/groups"), NOW);
        assertEquals("Solak", plan.activity());
        assertEquals("Bring your own food", plan.note(), "line breaks and invisible characters become one space");
        assertEquals(Instant.parse("2026-10-27T20:00:00Z"), plan.startsAt());
        assertEquals(List.of(new QuickGroups.Slot("Base", 1), new QuickGroups.Slot("DPS", 3)), plan.slots());
        assertEquals(4, plan.places());
        assertEquals("https://younglings.runeclans.com/groups", plan.link());
    }

    @Test
    void noSlotsIsOneAnyRoleOfTheGivenSize() {
        QuickGroups.Plan plan = QuickGroups.check(body("Raids", "2026-10-27T20:00:00Z", null).put("size", 2), NOW);
        assertEquals(List.of(new QuickGroups.Slot("Any", 2)), plan.slots());
        assertEquals(List.of(new QuickGroups.Slot("Any", 4)), QuickGroups.check(body("Raids", "2026-10-27T20:00:00Z", null), NOW).slots());
    }

    @Test
    void badGroupsAreRefusedInWords() {
        String later = "2026-10-27T20:00:00Z";
        assertThrows(IllegalArgumentException.class, () -> QuickGroups.check(body("   ", later, null), NOW));
        assertThrows(IllegalArgumentException.class, () -> QuickGroups.check(body("x".repeat(QuickGroups.MAX_ACTIVITY + 1), later, null), NOW));
        assertThrows(IllegalArgumentException.class, () -> QuickGroups.check(body("Solak", "tonight", null), NOW));
        assertThrows(IllegalArgumentException.class, () -> QuickGroups.check(body("Solak", "2026-10-27T17:00:00Z", null), NOW), "an hour ago");
        assertThrows(IllegalArgumentException.class, () -> QuickGroups.check(body("Solak", NOW.plus(Duration.ofDays(15)).toString(), null), NOW));
        assertThrows(IllegalArgumentException.class, () -> QuickGroups.check(body("Solak", later, slots("DPS", 1, "dps", 2)), NOW), "same role twice");
        assertThrows(IllegalArgumentException.class, () -> QuickGroups.check(body("Solak", later, slots("DPS", 0)), NOW));
        assertThrows(IllegalArgumentException.class, () -> QuickGroups.check(body("Solak", later, slots("DPS", 21)), NOW));
        assertThrows(IllegalArgumentException.class, () -> QuickGroups.check(body("Solak", later, slots("A", 20, "B", 20, "C", 1)), NOW), "41 places");
        assertThrows(IllegalArgumentException.class, () -> QuickGroups.check(body("Solak", later, slots("A", 1, "B", 1, "C", 1, "D", 1, "E", 1, "F", 1, "G", 1)), NOW));
        assertThrows(IllegalArgumentException.class, () -> QuickGroups.check(body("Solak", later, slots("", 1)), NOW));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> QuickGroups.check(body("Solak", later, slots("Tank", 1, "tank", 1)), NOW));
        assertTrue(e.getMessage().contains("twice"), e.getMessage());
    }

    @Test
    void aStartAMomentAgoIsNowAndALinkThatIsntAPageIsDropped() {
        QuickGroups.Plan plan = QuickGroups.check(body("Solak", "2026-10-27T17:55:00Z", null).put("link", "javascript:alert(1)"), NOW);
        assertEquals(NOW, plan.startsAt());
        assertNull(plan.link());
        assertNull(QuickGroups.check(body("Solak", "2026-10-27T19:00:00Z", null).put("link", "http://plain.example/x"), NOW).link(), "https only");
    }

    @Test
    void slotsAndLinkRoundTripThroughTheEventConfig() {
        QuickGroups.Plan plan = QuickGroups.check(body("Solak", "2026-10-27T20:00:00Z", slots("Base", 1, "DPS", 3)).put("link", "https://a.runeclans.com/"), NOW);
        String config = QuickGroups.config(plan);
        assertEquals(plan.slots(), QuickGroups.slotsOf(config));
        assertEquals("https://a.runeclans.com/", QuickGroups.linkOf(config));
        assertEquals(List.of(new QuickGroups.Slot("Any", 4)), QuickGroups.slotsOf("{}"), "an unreadable config is one Any role");
        assertNull(QuickGroups.linkOf("not json"));
    }

    @Test
    void takenCountsEachRoleWhateverItsCaseAndIgnoresUnknownRoles() {
        List<QuickGroups.Slot> slots = List.of(new QuickGroups.Slot("Base", 1), new QuickGroups.Slot("DPS", 3));
        Map<String, Integer> taken = QuickGroups.taken(slots, java.util.Arrays.asList("dps", "DPS", "Base", "Healer", null));
        assertEquals(Map.of("Base", 1, "DPS", 2), taken);
        assertFalse(QuickGroups.hasRoom(slots.get(0), taken.get("Base")));
        assertTrue(QuickGroups.hasRoom(slots.get(1), taken.get("DPS")));
        assertEquals("DPS", QuickGroups.slotNamed(slots, " dps ").role());
        assertNull(QuickGroups.slotNamed(slots, "Tank"));
    }

    @Test
    void groupsAreKnownByTheirTemplateAndListedTwoHoursPastTheirStart() {
        assertTrue(QuickGroups.isGroup(new EventRepository.Event(1, 2, QuickGroups.TEMPLATE, "Solak", "", Status.SIGNUPS, EventRules.Scope.PRIVATE,
                EventRules.ScoringMode.INDIVIDUAL, null, null, 0, "{}", 3, null, null, false)));
        assertFalse(QuickGroups.isGroup(null));
        assertEquals(Instant.parse("2026-10-27T22:00:00Z"), QuickGroups.endsAt(Instant.parse("2026-10-27T20:00:00Z")));
    }

    @Test
    void theDiscordPostNamesEachRoleAndWhoTookItAndEscapesMemberText() {
        String config = QuickGroups.config(QuickGroups.check(body("Solak", "2026-10-27T20:00:00Z", slots("Base", 1, "DPS", 3))
                .put("link", "https://a.runeclans.com/groups"), NOW));
        Group group = new Group(5, 6, "Solak", "No *leeches*", Instant.parse("2026-10-27T20:00:00Z"), null, 7, config, Status.SIGNUPS);
        List<Member> members = List.of(new Member(8, "Base", NOW), new Member(9, "DPS", NOW), new Member(10, "DPS", NOW));
        MessageEmbed embed = QuickGroupJobs.embed(group, members, id -> id == 7 ? "*Host*" : id == 8 ? "Ann" : id == 9 ? "@everyone" : null);
        assertEquals("Group: Solak", embed.getTitle());
        assertEquals("https://a.runeclans.com/groups", embed.getUrl());
        assertTrue(embed.getDescription().contains("<t:" + Instant.parse("2026-10-27T20:00:00Z").getEpochSecond() + ":F>"));
        assertTrue(embed.getDescription().contains("\\*Host\\*"), embed.getDescription());
        assertTrue(embed.getDescription().contains("No \\*leeches\\*"), embed.getDescription());
        assertTrue(embed.getDescription().contains("3 of 4"));
        assertEquals("Base (1/1)", embed.getFields().get(0).getName());
        assertEquals("Ann", embed.getFields().get(0).getValue());
        assertEquals("DPS (2/3)", embed.getFields().get(1).getName());
        assertEquals("@everyone, A member", embed.getFields().get(1).getValue(), "a name is shown as written (the post pings nobody)");

        Group closed = new Group(5, 6, "Solak", "", null, null, 7, config, Status.DONE);
        MessageEmbed shut = QuickGroupJobs.embed(closed, List.of(), id -> null);
        assertEquals("Closed: Solak", shut.getTitle());
        assertEquals("Open", shut.getFields().get(0).getValue());
        assertEquals("This group is closed.", shut.getFooter().getText());
    }
}
