package com.runeclans.bot.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code BOT_ROLE} (T-670): the default is the bot as it always was, and each role runs only its part. */
class BotRoleTest {
    @AfterEach
    void reset() {
        BotRole.install(BotRole.ALL);
    }

    @Test
    void unsetBlankOrUnknownIsAll() {
        assertEquals(BotRole.ALL, BotRole.parse(null));
        assertEquals(BotRole.ALL, BotRole.parse(""));
        assertEquals(BotRole.ALL, BotRole.parse("  "));
        assertEquals(BotRole.ALL, BotRole.parse("both"));
        assertEquals(BotRole.ALL, BotRole.parse("all"));
        assertEquals(BotRole.GATEWAY, BotRole.parse(" Gateway "));
        assertEquals(BotRole.API, BotRole.parse("API"));
    }

    @Test
    void eachRoleRunsItsPart() {
        assertTrue(BotRole.ALL.gateway());
        assertTrue(BotRole.ALL.internalApi());
        assertTrue(BotRole.ALL.outbox());

        assertTrue(BotRole.GATEWAY.gateway());
        assertFalse(BotRole.GATEWAY.internalApi(), "the website talks to the api process");
        assertTrue(BotRole.GATEWAY.outbox());

        assertFalse(BotRole.API.gateway(), "no gateway login");
        assertTrue(BotRole.API.internalApi());
        assertFalse(BotRole.API.outbox(), "jobs need the gateway's servers");
    }

    @Test
    void theProcessIsAllUntilStartUpSaysOtherwise() {
        assertEquals(BotRole.ALL, BotRole.current());
        BotRole.install(BotRole.API);
        assertEquals(BotRole.API, BotRole.current());
    }

    @Test
    void aSplitWaitsForEveryLaneToNameItsOwner() {
        assertFalse(BotRole.splitReady(), "waits for the api role to read servers through the gateway");
        assertEquals(BotRole.ALL, BotRole.checkedForStart(BotRole.ALL));
        IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> BotRole.checkedForStart(BotRole.API));
        assertTrue(refused.getMessage().contains("BOT_ROLE=api can't run yet"), refused.getMessage());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> BotRole.checkedForStart(BotRole.GATEWAY));
    }
}
