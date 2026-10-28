package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRoster.Gate;
import com.runeclans.bot.events.EventRoster.Setting;
import com.runeclans.bot.events.EventRoster.Verification;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventRosterTest {
    private static Setting asking(Gate gate) {
        return new Setting(true, gate, "Send a screenshot of your bank.");
    }

    @Test
    void anEventThatDoesntAskForVerificationCountsEveryoneAtEveryMoment() {
        Setting none = EventRoster.notRequired();
        for (Verification stored : Verification.values()) {
            for (Gate moment : Gate.values()) assertTrue(EventRoster.counts(stored, none, moment), stored + " at " + moment);
        }
        assertEquals(Verification.NOT_REQUIRED, EventRoster.effective(Verification.PENDING, none));
        assertEquals(Verification.NOT_REQUIRED, EventRoster.effective(Verification.REJECTED, none), "what was decided before the event stopped asking doesn't show");
    }

    @Test
    void aPlayerNobodyHasDecidedAboutIsPendingOnceTheEventAsks() {
        assertEquals(Verification.PENDING, EventRoster.effective(Verification.NOT_REQUIRED, asking(Gate.TEAMS)));
        assertEquals(Verification.PENDING, EventRoster.effective(null, asking(Gate.TEAMS)));
        assertEquals(Verification.VERIFIED, EventRoster.effective(Verification.VERIFIED, asking(Gate.TEAMS)));
    }

    @Test
    void aVerifiedOrWaivedPlayerAlwaysCountsAndARejectedOneNever() {
        for (Gate gate : Gate.values()) {
            for (Gate moment : Gate.values()) {
                assertTrue(EventRoster.counts(Verification.VERIFIED, asking(gate), moment));
                assertTrue(EventRoster.counts(Verification.WAIVED, asking(gate), moment));
                assertFalse(EventRoster.counts(Verification.REJECTED, asking(gate), moment), "turned down: gate " + gate + " moment " + moment);
            }
        }
    }

    @Test
    void aPendingPlayerCountsUntilTheGateAndNotFromThenOn() {
        // the gate is the moment a player must have passed by: before it they are on the roster, from it they are left out
        assertTrue(EventRoster.counts(Verification.PENDING, asking(Gate.TEAMS), Gate.SIGNUP), "on the roster while signups are open");
        assertFalse(EventRoster.counts(Verification.PENDING, asking(Gate.TEAMS), Gate.TEAMS), "left out of the teams");
        assertFalse(EventRoster.counts(Verification.PENDING, asking(Gate.TEAMS), Gate.START));

        assertTrue(EventRoster.counts(Verification.PENDING, asking(Gate.START), Gate.SIGNUP));
        assertTrue(EventRoster.counts(Verification.PENDING, asking(Gate.START), Gate.TEAMS), "with the gate at the start they can still be put in a team");
        assertFalse(EventRoster.counts(Verification.PENDING, asking(Gate.START), Gate.START));

        for (Gate moment : Gate.values()) assertFalse(EventRoster.counts(Verification.PENDING, asking(Gate.SIGNUP), moment), "gate at signup: never counted until approved");
    }

    @Test
    void movingTheGateChangesWhoCountsWithoutChangingAnyPlayer() {
        List<Verification> stored = List.of(Verification.VERIFIED, Verification.PENDING, Verification.REJECTED, Verification.WAIVED, Verification.PENDING);
        long atTeams = stored.stream().filter(v -> EventRoster.counts(v, asking(Gate.TEAMS), Gate.TEAMS)).count();
        long atStartForTeams = stored.stream().filter(v -> EventRoster.counts(v, asking(Gate.START), Gate.TEAMS)).count();
        assertEquals(2, atTeams, "verified and waived");
        assertEquals(4, atStartForTeams, "pending ones still count for teams when the gate is the start");
    }

    @Test
    void theTallyReadsEachPlayerAsTheEventSeesThem() {
        var tally = EventRoster.tally(List.of(Verification.VERIFIED, Verification.VERIFIED, Verification.WAIVED, Verification.PENDING, Verification.REJECTED, Verification.NOT_REQUIRED), asking(Gate.TEAMS));
        assertEquals(2, tally.verified());
        assertEquals(1, tally.waived());
        assertEquals(2, tally.pending(), "a player never decided about is pending");
        assertEquals(1, tally.rejected());
        assertEquals(3, tally.settled());
        assertEquals(3, tally.unsettled());
        var none = EventRoster.tally(List.of(Verification.PENDING, Verification.REJECTED), EventRoster.notRequired());
        assertEquals(0, none.unsettled(), "an event that doesn't ask has nobody to decide about");
    }

    @Test
    void onlyRealDecisionsCanBeSet() {
        assertTrue(EventRoster.settable(Verification.VERIFIED));
        assertTrue(EventRoster.settable(Verification.WAIVED));
        assertTrue(EventRoster.settable(Verification.REJECTED));
        assertTrue(EventRoster.settable(Verification.PENDING));
        assertFalse(EventRoster.settable(Verification.NOT_REQUIRED));
        assertFalse(EventRoster.settable(null));
    }

    @Test
    void whatPlayersAreToldComesFromTheInstructionsOrASensibleDefault() {
        assertEquals("Send a screenshot of your bank.", EventRoster.askText(asking(Gate.TEAMS)));
        assertTrue(EventRoster.askText(new Setting(true, Gate.TEAMS, "   ")).toLowerCase().contains("verification"));
        assertEquals(Gate.TEAMS, new Setting(true, null, null).gate(), "no gate named: before teams");
    }

    // ---------- in the event's settings ----------

    @Test
    void theSettingsReadTheModeAndTheVerificationAndDefaultToNeither() {
        EventSettings none = EventSettings.parse(DataObject.empty());
        assertEquals(EventPrep.Mode.OFF, none.setupMode());
        assertFalse(none.verification().required());
        EventSettings both = EventSettings.parse(DataObject.fromJson(
                "{\"setup\":{\"mode\":\"standard\"},\"verification\":{\"required\":true,\"gate\":\"start\",\"instructions\":\"  Bank screenshot  \"}}"));
        assertEquals(EventPrep.Mode.STANDARD, both.setupMode());
        assertTrue(both.verification().required());
        assertEquals(Gate.START, both.verification().gate());
        assertEquals("Bank screenshot", both.verification().instructions());
        assertEquals(List.of(), EventSettings.problems(DataObject.fromJson("{\"setup\":{\"mode\":\"FULL\"},\"verification\":{\"required\":true,\"gate\":\"SIGNUP\"}}")));
    }

    @Test
    void aBadModeOrGateOrLongInstructionsAreReportedInWords() {
        assertFalse(EventSettings.problems(DataObject.fromJson("{\"setup\":{\"mode\":\"sometimes\"}}")).isEmpty());
        assertFalse(EventSettings.problems(DataObject.fromJson("{\"verification\":{\"required\":true,\"gate\":\"later\"}}")).isEmpty());
        assertFalse(EventSettings.problems(DataObject.fromJson("{\"verification\":{\"instructions\":\"" + "x".repeat(EventRoster.MAX_INSTRUCTIONS + 1) + "\"}}")).isEmpty());
        assertFalse(EventSettings.problems(DataObject.fromJson("{\"setup\":5}")).isEmpty());
        // reading never throws, whatever is stored
        assertEquals(EventPrep.Mode.OFF, EventSettings.parse(DataObject.fromJson("{\"setup\":{\"mode\":\"sometimes\"}}")).setupMode());
        assertFalse(EventSettings.parse(DataObject.fromJson("{\"verification\":7}")).verification().required());
    }
}
