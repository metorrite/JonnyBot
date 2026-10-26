package com.younglings.bot.runescape;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuneMetricsSunsetTest {

    @Test
    void theSwitchStopsAtTheSunsetUnlessForced() {
        Instant before = RuneMetricsSwitch.SUNSET.minusSeconds(1);
        assertEquals(RuneMetricsSwitch.Mode.AUTO, RuneMetricsSwitch.parse(null));
        assertEquals(RuneMetricsSwitch.Mode.AUTO, RuneMetricsSwitch.parse("maybe"));
        assertTrue(RuneMetricsSwitch.on(RuneMetricsSwitch.Mode.AUTO, before));
        assertFalse(RuneMetricsSwitch.on(RuneMetricsSwitch.Mode.AUTO, RuneMetricsSwitch.SUNSET));
        assertTrue(RuneMetricsSwitch.on(RuneMetricsSwitch.parse(" ON "), RuneMetricsSwitch.SUNSET));
        assertFalse(RuneMetricsSwitch.on(RuneMetricsSwitch.parse("off"), before));
    }

    @Test
    void theBreakerPausesAfterManyFailuresInARowAndProbesAfterThePause() {
        RuneMetricsBreaker breaker = new RuneMetricsBreaker();
        long now = 1_000;
        for (int i = 1; i < RuneMetricsBreaker.FAILURES_TO_OPEN; i++) assertFalse(breaker.recordFailure(now));
        assertTrue(breaker.allow(now), "still asking below the threshold");
        assertTrue(breaker.recordFailure(now), "opening is reported once");
        assertFalse(breaker.allow(now + RuneMetricsBreaker.PAUSE_MS - 1));
        assertTrue(breaker.allow(now + RuneMetricsBreaker.PAUSE_MS), "one probe after the pause");
        assertFalse(breaker.recordFailure(now + RuneMetricsBreaker.PAUSE_MS), "a failed probe pauses again quietly");
        assertFalse(breaker.allow(now + RuneMetricsBreaker.PAUSE_MS + 1));
        assertTrue(breaker.recordAnswer(), "closing is reported once");
        assertTrue(breaker.allow(now + RuneMetricsBreaker.PAUSE_MS + 1));
        assertFalse(breaker.isOpen());
    }

    @Test
    void anAnswerResetsTheCount() {
        RuneMetricsBreaker breaker = new RuneMetricsBreaker();
        for (int i = 1; i < RuneMetricsBreaker.FAILURES_TO_OPEN; i++) breaker.recordFailure(0);
        assertFalse(breaker.recordAnswer());
        assertFalse(breaker.recordFailure(0), "the count started again");
    }
}
