package com.runeclans.bot.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-696: the process name the outbox claims jobs under. */
class ProcessIdTest {
    @Test
    void oneShortPlainNamePerProcess() {
        String id = ProcessId.get();
        assertEquals(id, ProcessId.get(), "the same for the whole run");
        assertTrue(id.length() <= 64, id);
        assertTrue(id.matches("[A-Za-z0-9._-]+-" + ProcessHandle.current().pid() + "-[0-9a-f]{8}"), id);
    }
}
