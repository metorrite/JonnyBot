package com.younglings.bot.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class PresenceBatchTest {
    private static final long GUILD = 7;
    private static final long OTHER = 8;

    @Test
    void theFirstMemberOfAServerSchedulesItsFlushTheRestJoinIt() {
        PresenceBatch batch = new PresenceBatch();
        assertTrue(batch.add(GUILD, 1, 0));
        assertFalse(batch.add(GUILD, 2, 0));
        assertFalse(batch.add(GUILD, 1, 0), "the same member twice is one request");
        assertTrue(batch.add(OTHER, 3, 0), "each server has its own flush");
        assertEquals(List.of(List.of(1L, 2L)), batch.drain(GUILD, 10));
        assertEquals(List.of(), batch.drain(GUILD, 10), "drained once");
    }

    @Test
    void aMemberAskedForIsntAskedAgainUntilTheCooldownEnds() {
        PresenceBatch batch = new PresenceBatch();
        batch.add(GUILD, 1, 0);
        batch.drain(GUILD, 0);
        assertFalse(batch.add(GUILD, 1, PresenceBatch.COOLDOWN_MS - 1), "flicking online and idle asks once");
        assertTrue(batch.add(GUILD, 1, PresenceBatch.COOLDOWN_MS));
    }

    @Test
    void largeBatchesAreSplitIntoGatewaySizedRequests() {
        PresenceBatch batch = new PresenceBatch();
        LongStream.rangeClosed(1, 250).forEach(id -> batch.add(GUILD, id, 0));
        List<List<Long>> requests = batch.drain(GUILD, 0);
        assertEquals(List.of(100, 100, 50), requests.stream().map(List::size).toList());
        assertEquals(1L, requests.getFirst().getFirst(), "in the order they came online");
    }

    @Test
    void theCooldownIsPerServerBecauseLoadingAMemberInOneServerDoesntCacheThemInAnother() {
        PresenceBatch batch = new PresenceBatch();
        assertTrue(batch.add(GUILD, 1, 0));
        assertEquals(List.of(List.of(1L)), batch.drain(GUILD, 0));
        // the same person's presence in a second server arrives after the first server's flush: that server still has to ask for them
        assertTrue(batch.add(OTHER, 1, 1_000), "a member asked for in one server is still asked for in another");
        assertEquals(List.of(List.of(1L)), batch.drain(OTHER, 1_000));
        assertFalse(batch.add(GUILD, 1, 2_000), "and each server keeps its own cooldown");
        assertFalse(batch.add(OTHER, 1, 2_000));
    }

    @Test
    void oldCooldownsAreForgottenOnTheNextFlush() {
        PresenceBatch batch = new PresenceBatch();
        batch.add(GUILD, 1, 0);
        batch.drain(GUILD, 0);
        batch.add(GUILD, 2, PresenceBatch.COOLDOWN_MS);
        batch.drain(GUILD, PresenceBatch.COOLDOWN_MS);
        assertTrue(batch.add(GUILD, 1, PresenceBatch.COOLDOWN_MS + 1), "the first member's cooldown ended and was dropped");
        assertFalse(batch.add(GUILD, 2, PresenceBatch.COOLDOWN_MS + 1), "the second member is still cooling down");
    }

    @Test
    void drainingAServerWithNothingWaitingAsksForNothing() {
        assertEquals(List.of(), new PresenceBatch().drain(GUILD, 0));
    }
}
