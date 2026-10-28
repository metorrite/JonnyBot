package com.runeclans.bot.devtools;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotLogBufferTest {
    @Test
    void onlyTheLastHalfHourIsKept() {
        SnapshotLogBuffer buffer = new SnapshotLogBuffer();
        long now = System.currentTimeMillis();
        buffer.clock = () -> now;
        buffer.add(new SnapshotLogBuffer.Entry(Instant.ofEpochMilli(now).minusSeconds(31 * 60), "ERROR", "a", "old"));
        buffer.add(new SnapshotLogBuffer.Entry(Instant.ofEpochMilli(now).minusSeconds(29 * 60), "WARN", "a", "recent"));
        var recent = buffer.recent();
        assertEquals(1, recent.size());
        assertEquals("recent", recent.get(0).message());
    }

    @Test
    void theBufferIsBounded() {
        SnapshotLogBuffer buffer = new SnapshotLogBuffer();
        for (int i = 0; i < SnapshotLogBuffer.CAPACITY + 50; i++) buffer.add(new SnapshotLogBuffer.Entry(Instant.now(), "WARN", "a", "m" + i));
        assertEquals(SnapshotLogBuffer.CAPACITY, buffer.recent().size());
    }

    @Test
    void realWarningsAndErrorsAreCapturedButInfoIsNot() {
        SnapshotLogBuffer buffer = new SnapshotLogBuffer();
        Logger log = LoggerFactory.getLogger("snapshot.buffer.test");
        log.info("just info {}", 1);
        log.warn("a warning {}", 2);
        log.error("an error", new IllegalStateException("kaboom"));
        var messages = buffer.recent().stream().filter(e -> e.logger().equals("snapshot.buffer.test")).toList();
        assertEquals(2, messages.size());
        assertTrue(messages.get(0).message().contains("a warning 2"));
        assertTrue(messages.get(1).message().contains("IllegalStateException: kaboom"));
    }
}
