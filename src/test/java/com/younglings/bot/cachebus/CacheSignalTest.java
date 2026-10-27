package com.younglings.bot.cachebus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheSignalTest {
    private static final long GUILD = Long.parseLong("1".repeat(18));

    @Test
    void aPayloadRoundTrips() {
        CacheSignal signal = new CacheSignal("auto-reply", GUILD);
        assertEquals("auto-reply:" + GUILD, signal.payload());
        assertEquals(Optional.of(signal), CacheSignal.parse(signal.payload()));
    }

    @Test
    void anythingElseIsIgnored() {
        for (String bad : new String[]{null, "", ":", "automod", "automod:", ":" + GUILD, "automod:0", "automod:-5", "automod:12x", "Automod:1",
                "auto mod:1", "automod:1:2", "automod:99999999999999999999999", "1automod:1", "automod: 1", "a".repeat(41) + ":1"}) {
            assertTrue(CacheSignal.parse(bad).isEmpty(), "should ignore " + bad);
        }
    }

    @Test
    void aBadSignalCannotBeBuilt() {
        assertThrows(IllegalArgumentException.class, () -> new CacheSignal("Bad", GUILD));
        assertThrows(IllegalArgumentException.class, () -> new CacheSignal("automod", 0));
    }

    @Test
    void theSwitchIsOffUnlessSetOn() {
        assertFalse(CacheBus.switchedOn(null));
        assertFalse(CacheBus.switchedOn(""));
        assertFalse(CacheBus.switchedOn("false"));
        assertFalse(CacheBus.switchedOn("yes"));
        assertTrue(CacheBus.switchedOn("true"));
        assertTrue(CacheBus.switchedOn(" TRUE "));
        assertTrue(CacheBus.switchedOn("1"));
    }

    @Test
    void sendingWhileOffDoesNothing() {
        // no bus was started in this JVM's unit tests: the call must neither throw nor wait
        CacheBus.changed("automod", GUILD);
        CacheBus.changed("Not an area", GUILD);
        CacheBus.changed("automod", -1);
    }

    @Test
    void theBackoffGrowsToAMinute() {
        List<Long> waits = new ArrayList<>();
        for (int failures = 0; failures <= 9; failures++) waits.add(CacheListener.backoffMs(failures));
        assertEquals(List.of(0L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L), waits);
        assertEquals(60_000L, CacheListener.backoffMs(1_000));
    }
}
