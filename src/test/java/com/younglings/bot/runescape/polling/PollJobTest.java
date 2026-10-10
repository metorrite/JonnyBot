package com.younglings.bot.runescape.polling;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PollJobTest {
    private PollJob job(Duration spreadOver) {
        return new PollJob("clan players", PollPriority.CLAN, Duration.ofHours(3), Duration.ZERO, spreadOver, Duration.ofMinutes(90), List::of);
    }

    @Test
    void aRunSpreadsItsPlayersEvenlyAcrossTheWindow() {
        List<PollRequest> requests = job(Duration.ofSeconds(100)).requestsFor(List.of("a", "b", "c", "d"));

        assertEquals(List.of(0L, 25L, 50L, 75L), requests.stream().map(r -> r.delay().toSeconds()).toList());
    }

    @Test
    void everyRequestCarriesTheJobsPriorityFreshnessAndName() {
        PollRequest request = job(Duration.ZERO).requestsFor(List.of("a")).get(0);

        assertEquals(PollPriority.CLAN, request.priority());
        assertEquals(Duration.ofMinutes(90), request.maxAge());
        assertEquals("clan players", request.source());
        assertEquals(Duration.ZERO, request.delay());
    }

    @Test
    void anEmptyListMakesNoRequests() {
        assertEquals(List.of(), job(Duration.ofHours(1)).requestsFor(List.of()));
    }

    @Test
    void aJobNeedsANameAndAPeriod() {
        assertThrows(IllegalArgumentException.class, () -> new PollJob(" ", PollPriority.CLAN, Duration.ofHours(1), null, null, null, List::of));
        assertThrows(IllegalArgumentException.class, () -> new PollJob("x", PollPriority.CLAN, Duration.ZERO, null, null, null, List::of));
    }
}
