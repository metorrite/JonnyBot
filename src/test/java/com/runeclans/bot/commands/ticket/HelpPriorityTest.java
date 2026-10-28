package com.runeclans.bot.commands.ticket;

import com.runeclans.bot.commands.ticket.HelpPriority.Tier;
import com.runeclans.bot.ticket.HelpExtras;
import com.runeclans.bot.ticket.TicketModels.HelpSettings;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/** Priority tiers (members, longtime guests, new guests) and the optional kill count requirement for new guests. */
class HelpPriorityTest {
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final HelpSettings SETTINGS = HelpSettings.defaults(1);
    private static final HelpExtras OFF = HelpExtras.defaults(1);
    private static final HelpExtras ON = new HelpExtras(1, false, 24, 1, false, "", true, 30, true, 120, "Master=50, Grandmaster=100");

    private static OffsetDateTime daysAgo(long days) {
        return NOW.minusSeconds(days * 86400).atOffset(ZoneOffset.UTC);
    }

    @Test
    void membersAreMembersWhateverTheirServerAge() {
        assertEquals(Tier.MEMBER, HelpPriority.tierOf(true, daysAgo(1), NOW, ON));
        assertEquals(Tier.MEMBER, HelpPriority.tierOf(true, null, NOW, ON));
    }

    @Test
    void aGuestBecomesLongtimeAfterTheConfiguredDays() {
        assertEquals(Tier.NEW_GUEST, HelpPriority.tierOf(false, daysAgo(29), NOW, ON));
        assertEquals(Tier.LONGTIME_GUEST, HelpPriority.tierOf(false, daysAgo(30), NOW, ON));
        assertEquals(Tier.LONGTIME_GUEST, HelpPriority.tierOf(false, daysAgo(400), NOW, ON));
        assertEquals(Tier.NEW_GUEST, HelpPriority.tierOf(false, null, NOW, ON), "when we can't tell, they are new");
    }

    @Test
    void withTiersOffEveryoneFollowsTheOldMemberAndGuestRules() {
        assertEquals(HelpRules.pingsFor(SETTINGS, true), HelpPriority.pingsFor(SETTINGS, OFF, Tier.MEMBER));
        assertEquals(HelpRules.pingsFor(SETTINGS, false), HelpPriority.pingsFor(SETTINGS, OFF, Tier.NEW_GUEST));
        assertEquals(HelpRules.pingsFor(SETTINGS, false), HelpPriority.pingsFor(SETTINGS, OFF, Tier.LONGTIME_GUEST), "a longtime guest is just a guest while tiers are off");
    }

    @Test
    void withTiersOnALongtimeGuestHasPingsOfTheirOwn() {
        assertEquals(HelpRules.pingsFor(SETTINGS, true), HelpPriority.pingsFor(SETTINGS, ON, Tier.MEMBER));
        assertEquals(HelpRules.pingsFor(SETTINGS, false), HelpPriority.pingsFor(SETTINGS, ON, Tier.NEW_GUEST));
        var longtime = HelpPriority.pingsFor(SETTINGS, ON, Tier.LONGTIME_GUEST);
        assertTrue(longtime.pingOnOpen());
        assertEquals(120, longtime.escalationHours());
        var never = HelpPriority.pingsFor(SETTINGS, new HelpExtras(1, false, 24, 1, false, "", true, 30, false, 0, ""), Tier.LONGTIME_GUEST);
        assertFalse(never.pingOnOpen());
        assertNull(never.escalationHours(), "0 hours means never");
    }

    @Test
    void membersAreHelpedBeforeLongtimeGuestsBeforeNewGuests() {
        // the default settings: members ping on open and escalate at 72h; new guests ping nobody; a longtime guest sits between
        assertTrue(HelpPriority.pingsFor(SETTINGS, ON, Tier.MEMBER).pingOnOpen());
        assertTrue(HelpPriority.pingsFor(SETTINGS, ON, Tier.LONGTIME_GUEST).pingOnOpen());
        assertFalse(HelpPriority.pingsFor(SETTINGS, ON, Tier.NEW_GUEST).pingOnOpen());
        assertTrue(HelpPriority.pingsFor(SETTINGS, ON, Tier.MEMBER).escalationHours() < HelpPriority.pingsFor(SETTINGS, ON, Tier.LONGTIME_GUEST).escalationHours());
    }

    @Test
    void onlyNewGuestsAreAskedForKillCountsAndOnlyWhereTheServerNamedOne() {
        assertTrue(HelpPriority.needsAttemptsForKc(ON, Tier.NEW_GUEST, "Master", OptionalInt.empty()), "no data: describe your attempts instead");
        assertTrue(HelpPriority.needsAttemptsForKc(ON, Tier.NEW_GUEST, "master", OptionalInt.of(10)), "tracked but short");
        assertFalse(HelpPriority.needsAttemptsForKc(ON, Tier.NEW_GUEST, "Master", OptionalInt.of(50)), "tracked kills meet it");
        assertFalse(HelpPriority.needsAttemptsForKc(ON, Tier.NEW_GUEST, "Hard", OptionalInt.empty()), "no requirement for that tier");
        assertFalse(HelpPriority.needsAttemptsForKc(ON, Tier.LONGTIME_GUEST, "Master", OptionalInt.empty()));
        assertFalse(HelpPriority.needsAttemptsForKc(ON, Tier.MEMBER, "Master", OptionalInt.empty()));
        assertFalse(HelpPriority.needsAttemptsForKc(OFF, Tier.NEW_GUEST, "Master", OptionalInt.empty()), "off by default");
    }

    @Test
    void killLinesAreReadForTheBossAskedAbout() {
        assertEquals(12, HelpPriority.killsIn("I killed 12 Vorago.", "Vorago"));
        assertEquals(1, HelpPriority.killsIn("I killed Vorago.", "vorago"));
        assertEquals(1500, HelpPriority.killsIn("I killed 1,500 Araxxor.", "Araxxor"));
        assertEquals(0, HelpPriority.killsIn("I killed 12 Solak.", "Vorago"));
        assertEquals(0, HelpPriority.killsIn("Quest complete: Cook's Assistant", "Vorago"));
        assertEquals(0, HelpPriority.killsIn(null, "Vorago"));
    }

    @Test
    void killCountRequirementsAreParsedAndChecked() {
        assertEquals(50, ON.kcRequirement("Master"));
        assertEquals(100, ON.kcRequirement(" grandmaster "));
        assertNull(ON.kcRequirement("Elite"));
        assertNull(OFF.kcRequirement("Master"));
        assertTrue(ON.problems().isEmpty());
        assertFalse(new HelpExtras(1, false, 24, 1, false, "", true, 30, true, 120, "Master 50").problems().isEmpty());
        assertFalse(new HelpExtras(1, false, 24, 1, false, "", true, 30, true, 120, "Master=0").problems().isEmpty());
        assertFalse(new HelpExtras(1, false, 24, 1, false, "", true, 0, true, 120, "").problems().isEmpty(), "a longtime guest is at least a day old");
        assertFalse(new HelpExtras(1, false, 24, 1, false, "", true, 30, true, 9999, "").problems().isEmpty());
    }

    @Test
    void aShortfallReusesTheAttemptsRuleForThatTierOnly() {
        var ca = HelpPanels.ca(1, "CA Help");
        var tierOptions = java.util.List.of(new com.runeclans.bot.ticket.TicketModels.Option(11, 2, 0, "Hard", null, null), new com.runeclans.bot.ticket.TicketModels.Option(12, 2, 1, "Master", null, null));
        var fields = java.util.List.of(
                new com.runeclans.bot.ticket.TicketModels.Field(1, 1, 0, "Which boss?", com.runeclans.bot.ticket.TicketModels.FieldKind.SHORT, true, null, 100, java.util.List.of(), com.runeclans.bot.ticket.TicketModels.FieldPurpose.BOSS),
                new com.runeclans.bot.ticket.TicketModels.Field(2, 1, 1, "Tier", com.runeclans.bot.ticket.TicketModels.FieldKind.SELECT, true, null, null, tierOptions, com.runeclans.bot.ticket.TicketModels.FieldPurpose.TIER),
                new com.runeclans.bot.ticket.TicketModels.Field(4, 1, 3, "Attempts?", com.runeclans.bot.ticket.TicketModels.FieldKind.CHECKBOX, false, null, null, java.util.List.of(), com.runeclans.bot.ticket.TicketModels.FieldPurpose.ATTEMPTS));
        var settings = HelpPriority.requiringAttemptsFor(SETTINGS, "Master");
        // Master with no attempts described: asked for them
        assertTrue(HelpRules.attemptsProblem(settings, ca.panel(), fields, Map.of(1L, "Vorago", 2L, "12", 4L, "false"), false).isPresent());
        // Master with attempts described: fine
        assertTrue(HelpRules.attemptsProblem(settings, ca.panel(), fields, Map.of(1L, "Vorago", 2L, "12", 4L, "true"), false).isEmpty());
        // Hard is not the tier with a shortfall, so it is never asked
        assertTrue(HelpRules.attemptsProblem(settings, ca.panel(), fields, Map.of(1L, "Vorago", 2L, "11", 4L, "false"), false).isEmpty());
        // and a member is never asked
        assertTrue(HelpRules.attemptsProblem(settings, ca.panel(), fields, Map.of(1L, "Vorago", 2L, "12", 4L, "false"), true).isEmpty());
    }
}
