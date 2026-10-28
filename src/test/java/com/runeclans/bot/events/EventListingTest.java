package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventListingTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private static Event event(Scope scope, Status status, boolean archived, String config, Instant ends) {
        return new Event(7, 1, "bingo", "Open Bingo", "desc", status, scope, ScoringMode.INDIVIDUAL, NOW, ends, 0, config, 9, NOW, null, archived);
    }

    private static Event event(Scope scope, Status status) {
        return event(scope, status, false, "{}", NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void onlyEventsTheHostOpenedAreListed() {
        assertFalse(EventListing.listed(event(Scope.PRIVATE, Status.SIGNUPS), NOW), "private events never leave the server");
        assertTrue(EventListing.listed(event(Scope.PUBLIC, Status.SIGNUPS), NOW));
        assertTrue(EventListing.listed(event(Scope.ALLIANCE, Status.RUNNING), NOW));
        assertFalse(EventListing.listed(event(Scope.PUBLIC, Status.DRAFT), NOW));
        assertFalse(EventListing.listed(event(Scope.PUBLIC, Status.CANCELLED), NOW));
        assertFalse(EventListing.listed(event(Scope.PUBLIC, Status.SIGNUPS, true, "{}", null), NOW), "archived");
        assertFalse(EventListing.listed(event(Scope.PUBLIC, Status.SIGNUPS, false, "{\"hub\":{\"takedown\":{\"reason\":\"spam\"}}}", null), NOW), "taken down");
        assertTrue(EventListing.listed(event(Scope.PUBLIC, Status.DONE, false, "{}", NOW.minus(Duration.ofDays(13))), NOW), "finished recently");
        assertFalse(EventListing.listed(event(Scope.PUBLIC, Status.DONE, false, "{}", NOW.minus(Duration.ofDays(15))), NOW), "finished long ago");
    }

    @Test
    void membersOfTheHostSeeTheirOwnServersEventsWhateverTheVisibility() {
        assertTrue(EventListing.shownToMembers(event(Scope.PRIVATE, Status.SIGNUPS), NOW), "only this server = its members");
        assertTrue(EventListing.shownToMembers(event(Scope.PUBLIC, Status.SIGNUPS, false, "{\"hub\":{\"takedown\":{\"reason\":\"spam\"}}}", null), NOW),
                "a takedown only takes it off the public hub");
        assertFalse(EventListing.shownToMembers(event(Scope.PRIVATE, Status.DRAFT), NOW));
        assertFalse(EventListing.shownToMembers(event(Scope.PRIVATE, Status.CANCELLED), NOW));
        assertFalse(EventListing.shownToMembers(event(Scope.PRIVATE, Status.SIGNUPS, true, "{}", null), NOW), "archived");
        assertFalse(EventListing.shownToMembers(event(Scope.PRIVATE, Status.DONE, false, "{}", NOW.minus(Duration.ofDays(15))), NOW), "finished long ago");
    }

    @Test
    void membersSeeStandingsByTheEventsOwnRule() {
        assertTrue(EventListing.standingsShownToMember(event(Scope.PRIVATE, Status.RUNNING), true), "players see them by default");
        assertFalse(EventListing.standingsShownToMember(event(Scope.PRIVATE, Status.RUNNING), false), "other members don't by default");
        String server = "{\"standings\":{\"visibility\":\"SERVER\"}}";
        assertTrue(EventListing.standingsShownToMember(event(Scope.PRIVATE, Status.RUNNING, false, server, null), false));
        String staff = "{\"standings\":{\"visibility\":\"STAFF\"}}";
        assertFalse(EventListing.standingsShownToMember(event(Scope.PRIVATE, Status.RUNNING, false, staff, null), true));
        String later = "{\"standings\":{\"visibility\":\"HIDDEN_UNTIL_END\"}}";
        assertFalse(EventListing.standingsShownToMember(event(Scope.PRIVATE, Status.RUNNING, false, later, null), true));
        assertTrue(EventListing.standingsShownToMember(event(Scope.PRIVATE, Status.DONE, false, later, null), true));
    }

    @Test
    void allianceEventsAreOnlyForAlliedViewers() {
        assertTrue(EventListing.visible(event(Scope.PUBLIC, Status.SIGNUPS), false));
        assertFalse(EventListing.visible(event(Scope.ALLIANCE, Status.SIGNUPS), false));
        assertTrue(EventListing.visible(event(Scope.ALLIANCE, Status.SIGNUPS), true));
        assertFalse(EventListing.visible(event(Scope.PRIVATE, Status.SIGNUPS), true));
    }

    @Test
    void standingsNeedTheHostsSwitchAndServerWideStandings() {
        assertFalse(EventListing.standingsShown(event(Scope.PUBLIC, Status.RUNNING)), "off by default");
        assertFalse(EventListing.standingsShown(event(Scope.PUBLIC, Status.RUNNING, false, "{\"hub\":{\"publicStandings\":true}}", null)), "standings are for participants");
        assertTrue(EventListing.standingsShown(event(Scope.PUBLIC, Status.RUNNING, false, "{\"hub\":{\"publicStandings\":true},\"standings\":{\"visibility\":\"SERVER\"}}", null)));
        assertFalse(EventListing.standingsShown(event(Scope.PUBLIC, Status.RUNNING, false, "{\"hub\":{\"publicStandings\":false},\"standings\":{\"visibility\":\"SERVER\"}}", null)));
    }

    @Test
    void theCardDescriptionIsShort() {
        assertEquals("", EventListing.shortDescription(null));
        assertEquals("Hi", EventListing.shortDescription("  Hi  "));
        String longText = "word ".repeat(100);
        String cut = EventListing.shortDescription(longText);
        assertTrue(cut.length() <= EventListing.SHORT_DESCRIPTION + 1 && cut.endsWith("…"), cut);
    }

    @Test
    void aSettingsSaveCanNeitherRemoveNorAddATakedown() {
        DataObject before = DataObject.fromJson("{\"hub\":{\"takedown\":{\"reason\":\"spam\"},\"publicStandings\":false}}");
        DataObject saved = DataObject.fromJson("{\"hub\":{\"publicStandings\":true}}");
        EventService.keepTakedown(before, saved);
        assertEquals("spam", EventListing.takedownReason(saved));
        assertTrue(saved.getObject("hub").getBoolean("publicStandings"), "the host's own hub settings are kept");
        DataObject clean = DataObject.fromJson("{}");
        DataObject sneaky = DataObject.fromJson("{\"hub\":{\"takedown\":{\"reason\":\"x\"}}}");
        EventService.keepTakedown(clean, sneaky);
        assertFalse(EventListing.takenDown(sneaky));
        assertNull(EventListing.takedownReason(sneaky));
    }
}
