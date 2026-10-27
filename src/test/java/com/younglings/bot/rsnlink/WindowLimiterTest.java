package com.younglings.bot.rsnlink;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowLimiterTest {
    @Test
    void allowsExactlyNInAnyWindowThenSaysHowLongToWait() {
        WindowLimiter limiter = new WindowLimiter(new LinkLimits.Window(3, Duration.ofMinutes(1)));
        long t = 1_000_000;
        assertEquals(0, limiter.tryAcquire("k", t));
        assertEquals(0, limiter.tryAcquire("k", t + 1_000));
        assertEquals(0, limiter.tryAcquire("k", t + 2_000));
        assertFalse(limiter.wouldAllow("k", t + 3_000));
        assertEquals(57, limiter.tryAcquire("k", t + 3_000));
        assertEquals(0, limiter.tryAcquire("other", t + 3_000), "keys are separate");
        assertTrue(limiter.wouldAllow("k", t + 60_000), "the first one has aged out");
        assertEquals(0, limiter.tryAcquire("k", t + 60_000));
    }

    @Test
    void waitIsRoundedUpToWholeSecondsAndNeverZero() {
        assertEquals(57, WindowLimiter.waitSeconds(1_000_000, 60_000, 1_003_000));
        assertEquals(1, WindowLimiter.waitSeconds(1_000_000, 60_000, 1_059_999));
        assertEquals(1, WindowLimiter.waitSeconds(1_000_000, 60_000, 1_060_000), "a stale oldest hit still answers at least 1");
        assertEquals(2, WindowLimiter.waitSeconds(1_000_000, 60_000, 1_058_999));
    }
}
