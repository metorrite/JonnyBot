package com.younglings.bot.eventannounce;

import com.younglings.bot.eventannounce.EventAnnounceRules.Card;
import com.younglings.bot.eventannounce.EventAnnounceRules.Settings;
import com.younglings.bot.eventannounce.EventAnnounceRules.State;
import com.younglings.bot.eventannounce.EventAnnounceRules.Step;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventAnnounceRulesTest {
    @Test
    void theAnnouncerIsOffUntilItIsOnAndHasAChannel() {
        assertFalse(Settings.defaults(1).active());
        assertFalse(Settings.defaults(1).enabled());
        assertFalse(new Settings(1, true, null, false, true).active(), "on but no channel");
        assertFalse(new Settings(1, false, 5L, false, true).active(), "a channel but off");
        assertTrue(new Settings(1, true, 5L, false, true).active());
    }

    @Test
    void aRoleIsMadeOnlyWhileTheServerHasRoomUnderTwoHundredAndFifty() {
        assertTrue(EventAnnounceRules.canMakeRole(0));
        assertTrue(EventAnnounceRules.canMakeRole(EventAnnounceRules.ROLE_LIMIT - EventAnnounceRules.ROLE_HEADROOM - 1));
        assertFalse(EventAnnounceRules.canMakeRole(EventAnnounceRules.ROLE_LIMIT - EventAnnounceRules.ROLE_HEADROOM));
        assertFalse(EventAnnounceRules.canMakeRole(250));
    }

    @Test
    void theRoleNameIsCleanedAndCut() {
        assertEquals("Event: Raid night", EventAnnounceRules.roleName("Raid night"));
        assertEquals("Event: everyone", EventAnnounceRules.roleName("@everyone"));
        assertEquals("Event: event", EventAnnounceRules.roleName("   "));
        assertEquals("Event: event", EventAnnounceRules.roleName(null));
        assertEquals(EventAnnounceRules.MAX_ROLE_NAME, EventAnnounceRules.roleName("x".repeat(300)).length());
        assertEquals("Event: ab", EventAnnounceRules.roleName("a​b"));
    }

    @Test
    void aScheduledEventIsOpenAndShowsWhenAndWhere() {
        Card card = EventAnnounceRules.card("Raid night", "Bring food", 1_800_000_000L, 1_800_003_600L, "<#42>", State.SCHEDULED);
        assertTrue(card.open());
        assertEquals("📅 Raid night", card.title());
        assertTrue(card.when().contains("<t:1800000000:F>") && card.when().contains("<t:1800000000:R>") && card.when().contains("Until <t:1800003600:F>"));
        assertEquals("<#42>", card.where());
        assertEquals("Bring food", card.description());
    }

    @Test
    void anActiveFinishedOrCancelledEventIsNotOpenAndSaysSo() {
        Card active = EventAnnounceRules.card("E", null, 1L, null, null, State.ACTIVE);
        Card done = EventAnnounceRules.card("E", null, 1L, null, null, State.COMPLETED);
        Card cancelled = EventAnnounceRules.card("E", null, 1L, null, null, State.CANCELED);
        assertFalse(active.open() || done.open() || cancelled.open());
        assertTrue(active.title().contains("is on now"));
        assertTrue(cancelled.title().contains("cancelled"));
        assertEquals("This event has finished.", done.footer());
        assertEquals("", active.description());
        assertFalse(active.when().contains("Until"));
    }

    @Test
    void aLongDescriptionIsCutAndAMissingNameHasAFallback() {
        assertTrue(EventAnnounceRules.card("E", "x".repeat(2000), 1L, null, null, State.SCHEDULED).description().length() <= EventAnnounceRules.MAX_DESCRIPTION + 1);
        assertEquals("📅 Event", EventAnnounceRules.card("  ", null, 1L, null, null, State.SCHEDULED).title());
    }

    @Test
    void theStartIsAnnouncedOnceAndTheEndOnce() {
        assertEquals(Step.START, EventAnnounceRules.step(State.SCHEDULED, State.ACTIVE, false));
        assertEquals(Step.NONE, EventAnnounceRules.step(State.SCHEDULED, State.ACTIVE, true), "already announced");
        assertEquals(Step.NONE, EventAnnounceRules.step(State.ACTIVE, State.ACTIVE, true), "an edit while it runs");
        assertEquals(Step.NONE, EventAnnounceRules.step(State.SCHEDULED, State.SCHEDULED, false), "an edit before it starts is not a start");
        assertEquals(Step.END, EventAnnounceRules.step(State.ACTIVE, State.COMPLETED, true));
        assertEquals(Step.END, EventAnnounceRules.step(State.SCHEDULED, State.CANCELED, false));
        assertEquals(Step.NONE, EventAnnounceRules.step(State.COMPLETED, State.COMPLETED, true), "an edit to a finished event ends nothing twice");
        assertEquals(Step.NONE, EventAnnounceRules.step(State.CANCELED, State.COMPLETED, true));
    }

    @Test
    void onlyScheduledOrActiveEventsNeedTheirRole() {
        assertTrue(EventAnnounceRules.needsRole(State.SCHEDULED));
        assertTrue(EventAnnounceRules.needsRole(State.ACTIVE));
        assertFalse(EventAnnounceRules.needsRole(State.COMPLETED));
        assertFalse(EventAnnounceRules.needsRole(State.CANCELED));
        assertEquals(State.ACTIVE, EventAnnounceRules.state("active"));
        assertEquals(State.SCHEDULED, EventAnnounceRules.state("whatever"));
    }
}
