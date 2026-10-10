package com.younglings.bot.runescape.polling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateBucketTest {
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private RateBucket bucket;

    @BeforeEach
    void setUp() {
        bucket = new RateBucket(3, 1_000, clock::get);
    }

    private void spend(int requests) {
        for (int i = 0; i < requests; i++) {
            assertEquals(0, bucket.waitMs());
            bucket.consume();
        }
    }

    @Test
    void aFullBucketLetsABurstThroughThenHoldsToTheSteadyRate() {
        spend(3);

        assertEquals(1_000, bucket.waitMs());
        clock.addAndGet(400);
        assertEquals(600, bucket.waitMs());
        clock.addAndGet(600);
        assertEquals(0, bucket.waitMs());
    }

    @Test
    void quietTimeStoresUpAtMostTheBurstSize() {
        spend(3);
        clock.addAndGet(60_000);

        assertEquals(3.0, bucket.tokens(), 1e-9);
        spend(3);
        assertTrue(bucket.waitMs() > 0);
    }

    @Test
    void aRateLimitEmptiesTheBucketAndDoublesTheInterval() {
        bucket.recordResult(true);

        assertEquals(2, bucket.multiplier());
        assertEquals(2_000, bucket.intervalMs());
        assertEquals(2_000, bucket.waitMs());
    }

    @Test
    void theSlowdownStopsAtTheCeiling() {
        for (int i = 0; i < 10; i++) bucket.recordResult(true);

        assertEquals(RateBucket.MAX_MULTIPLIER, bucket.multiplier());
    }

    @Test
    void aCalmRunHalvesTheIntervalAgain() {
        bucket.recordResult(true);
        bucket.recordResult(true);
        assertEquals(4, bucket.multiplier());

        for (int i = 0; i < RateBucket.CALM_REQUESTS_TO_RELAX; i++) bucket.recordResult(false);
        assertEquals(2, bucket.multiplier());
        for (int i = 0; i < RateBucket.CALM_REQUESTS_TO_RELAX; i++) bucket.recordResult(false);
        assertEquals(1, bucket.multiplier());
    }

    @Test
    void aRateLimitInTheMiddleOfACalmRunRestartsTheCount() {
        bucket.recordResult(true);
        for (int i = 0; i < RateBucket.CALM_REQUESTS_TO_RELAX - 1; i++) bucket.recordResult(false);
        bucket.recordResult(true);
        for (int i = 0; i < RateBucket.CALM_REQUESTS_TO_RELAX - 1; i++) bucket.recordResult(false);

        assertEquals(4, bucket.multiplier());
    }

    @Test
    void perMinuteBuildsTheIntervalFromTheRate() {
        RateBucket perMinute = RateBucket.perMinute(10, 6, clock::get);

        assertEquals(6_000, perMinute.intervalMs());
        assertEquals(6.0, perMinute.capacity());
    }
}
