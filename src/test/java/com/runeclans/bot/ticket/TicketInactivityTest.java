package com.runeclans.bot.ticket;

import com.runeclans.bot.ticket.TicketInactivity.Action;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketInactivityTest {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private static Instant ago(Duration d) {
        return NOW.minus(d);
    }

    @Test
    void aRecentlyActiveTicketIsLeftAlone() {
        assertEquals(Action.NOTHING, TicketInactivity.decide(ago(Duration.ofHours(1)), false, NOW, 7));
        assertEquals(Action.NOTHING, TicketInactivity.decide(ago(Duration.ofDays(5)), false, NOW, 7));
    }

    @Test
    void aTicketIsWarnedADayBeforeItWouldClose() {
        assertEquals(Action.WARN, TicketInactivity.decide(ago(Duration.ofDays(6)), false, NOW, 7));
        assertEquals(Action.WARN, TicketInactivity.decide(ago(Duration.ofDays(9)), false, NOW, 7));
    }

    @Test
    void aOneDaySettingStillGivesHalfADayBeforeTheWarning() {
        assertEquals(Action.NOTHING, TicketInactivity.decide(ago(Duration.ofHours(11)), false, NOW, 1));
        assertEquals(Action.WARN, TicketInactivity.decide(ago(Duration.ofHours(12)), false, NOW, 1));
    }

    @Test
    void afterTheWarningItClosesOnlyOnceTheGraceHasPassed() {
        assertEquals(Action.NOTHING, TicketInactivity.decide(ago(Duration.ofHours(23)), true, NOW, 7));
        assertEquals(Action.CLOSE, TicketInactivity.decide(ago(Duration.ofHours(24)), true, NOW, 7));
        assertEquals(Action.CLOSE, TicketInactivity.decide(ago(Duration.ofDays(3)), true, NOW, 7));
    }

    @Test
    void aReplyAfterTheWarningMakesItAnOrdinaryTicketAgain() {
        // the reply is now the last message and is not the warning
        assertEquals(Action.NOTHING, TicketInactivity.decide(ago(Duration.ofMinutes(5)), false, NOW, 7));
    }

    @Test
    void theWarningIsRecognisedByItsStartAndNamesWhenItCloses() {
        String text = TicketInactivity.warningText(NOW.plus(Duration.ofHours(24)));

        assertTrue(text.startsWith(TicketInactivity.WARNING_MARKER));
        assertTrue(text.contains("<t:" + NOW.plus(Duration.ofHours(24)).getEpochSecond() + ":R>"));
    }

    @Test
    void daysAreValidatedAndNullMeansNever() {
        assertTrue(TicketInactivity.validDays(null));
        assertTrue(TicketInactivity.validDays(1));
        assertTrue(TicketInactivity.validDays(TicketInactivity.MAX_DAYS));
        assertFalse(TicketInactivity.validDays(0));
        assertFalse(TicketInactivity.validDays(TicketInactivity.MAX_DAYS + 1));
    }

    @Test
    void theCloseReasonSaysHowLong() {
        assertEquals("Closed automatically after 1 day without a message", TicketInactivity.closeReason(1));
        assertEquals("Closed automatically after 7 days without a message", TicketInactivity.closeReason(7));
    }
}
