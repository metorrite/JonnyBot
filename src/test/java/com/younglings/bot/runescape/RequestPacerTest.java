package com.younglings.bot.runescape;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestPacerTest {
    private AtomicLong clock;
    private RequestPacer pacer;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000_000);
        pacer = new RequestPacer(2_000, clock::get);
    }

    @Test
    void theFirstRequestGoesImmediately() {
        assertEquals(0, pacer.reserveSlot());
    }

    @Test
    void requestsFromDifferentPassesAtTheSameMomentAreSpacedOutOneIntervalApart() {
        assertEquals(0, pacer.reserveSlot());
        assertEquals(2_000, pacer.reserveSlot());
        assertEquals(4_000, pacer.reserveSlot());
    }

    @Test
    void aPassSlowerThanTheIntervalNeverWaits() {
        pacer.reserveSlot();
        clock.addAndGet(180_000); // one request every three minutes, as a 3-hour window over a clan does

        assertEquals(0, pacer.reserveSlot());
        clock.addAndGet(180_000);
        assertEquals(0, pacer.reserveSlot());
    }

    @Test
    void aRateLimitDoublesTheIntervalAndPushesTheNextSlotOut() {
        pacer.reserveSlot();
        pacer.recordResult(true);

        assertEquals(2, pacer.stats().multiplier());
        assertEquals(4_000, pacer.stats().intervalMs());
        assertEquals(4_000, pacer.reserveSlot(), "the next request waits out the new, longer interval");
    }

    @Test
    void theIntervalStopsGrowingAtEightTimesTheBase() {
        for (int i = 0; i < 10; i++) pacer.recordResult(true);

        assertEquals(RequestPacer.MAX_MULTIPLIER, pacer.stats().multiplier());
        assertEquals(16_000, pacer.stats().intervalMs());
    }

    @Test
    void aCalmRunHalvesTheIntervalAgainUntilItIsBackToNormal() {
        pacer.recordResult(true);
        pacer.recordResult(true); // x4

        for (int i = 0; i < RequestPacer.CALM_REQUESTS_TO_RELAX; i++) pacer.recordResult(false);
        assertEquals(2, pacer.stats().multiplier());

        for (int i = 0; i < RequestPacer.CALM_REQUESTS_TO_RELAX; i++) pacer.recordResult(false);
        assertEquals(1, pacer.stats().multiplier());
        assertEquals(2_000, pacer.stats().intervalMs());
    }

    @Test
    void aRateLimitInTheMiddleOfACalmRunRestartsTheCount() {
        pacer.recordResult(true); // x2
        for (int i = 0; i < RequestPacer.CALM_REQUESTS_TO_RELAX - 1; i++) pacer.recordResult(false);
        pacer.recordResult(true); // x4, streak reset

        for (int i = 0; i < RequestPacer.CALM_REQUESTS_TO_RELAX - 1; i++) pacer.recordResult(false);
        assertEquals(4, pacer.stats().multiplier(), "one short of a full calm run");
    }

    @Test
    void statsCountRequestsRateLimitsAndTimeSpentWaiting() {
        RequestPacer.Stats before = pacer.stats();
        pacer.reserveSlot();
        pacer.reserveSlot();
        pacer.recordResult(true);

        RequestPacer.Stats pass = pacer.stats().minus(before);

        assertEquals(2, pass.requests());
        assertEquals(1, pass.rateLimited());
        assertEquals(2_000, pass.waitedMs());
    }
}
