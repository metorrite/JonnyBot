package com.runeclans.bot.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code BOT_ROLE} (T-670): the default is the bot as it always was, and each role runs only its part. */
class BotRoleTest {
    @AfterEach
    void reset() {
        BotRole.install(BotRole.ALL);
    }

    @Test
    void readsEachRoleAndUnsetIsAll() {
        assertEquals(BotRole.ALL, BotRole.parse(null));
        assertEquals(BotRole.ALL, BotRole.parse(""));
        assertEquals(BotRole.ALL, BotRole.parse("  "));
        assertThrows(IllegalStateException.class, () -> BotRole.parse("both"), "a typo stops the process now that a split is possible");
        assertEquals(BotRole.ALL, BotRole.parse("all"));
        assertEquals(BotRole.GATEWAY, BotRole.parse(" Gateway "));
        assertEquals(BotRole.API, BotRole.parse("API"));
    }

    @Test
    void aMistypedRoleStopsTheProcessOnceASplitIsPossible() {
        assertEquals(BotRole.ALL, BotRole.parse("gatway", false), "one process: all is still the bot as before");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> BotRole.parse(" gatway ", true));
        assertTrue(refused.getMessage().contains("'gatway'"), refused.getMessage());
        assertEquals(BotRole.ALL, BotRole.parse(" ", true), "unset is still all");
        assertEquals(BotRole.GATEWAY, BotRole.parse("gateway", true));
    }

    @Test
    void eachRoleRunsItsPart() {
        assertTrue(BotRole.ALL.gateway());
        assertTrue(BotRole.ALL.outbox());

        assertTrue(BotRole.GATEWAY.gateway());
        assertFalse(BotRole.GATEWAY.forwardsToGateway());
        assertFalse(BotRole.ALL.forwardsToGateway(), "one process answers everything itself");
        assertTrue(BotRole.GATEWAY.outbox());

        assertFalse(BotRole.API.gateway(), "no gateway login");
        assertTrue(BotRole.API.forwardsToGateway(), "it has no servers or members of its own");
        assertFalse(BotRole.API.outbox(), "jobs need the gateway's servers");
    }

    @Test
    void theProcessIsAllUntilStartUpSaysOtherwise() {
        assertEquals(BotRole.ALL, BotRole.current());
        BotRole.install(BotRole.API);
        assertEquals(BotRole.API, BotRole.current());
    }

    @Test
    void aSplitProcessStartsOnlyWithWhatItNeeds() {
        assertTrue(BotRole.splitReady(), "every lane has an owner (T-702) and the api role asks the gateway (T-746)");
        assertEquals(BotRole.ALL, BotRole.checkedForStart(BotRole.ALL, false, false, false), "one process needs nothing new");
        assertEquals(BotRole.GATEWAY, BotRole.checkedForStart(BotRole.GATEWAY, false, true, true));
        assertEquals(BotRole.API, BotRole.checkedForStart(BotRole.API, true, true, true));

        IllegalStateException noSignals = assertThrows(IllegalStateException.class, () -> BotRole.checkedForStart(BotRole.GATEWAY, false, false, true));
        assertTrue(noSignals.getMessage().contains("BOT_CACHE_NOTIFY"), noSignals.getMessage());
        assertThrows(IllegalStateException.class, () -> BotRole.checkedForStart(BotRole.API, true, false, true));
        IllegalStateException noApi = assertThrows(IllegalStateException.class, () -> BotRole.checkedForStart(BotRole.GATEWAY, false, true, false));
        assertTrue(noApi.getMessage().contains("INTERNAL_API_SECRET"), noApi.getMessage());
        IllegalStateException noGateway = assertThrows(IllegalStateException.class, () -> BotRole.checkedForStart(BotRole.API, false, true, true));
        assertTrue(noGateway.getMessage().contains("GATEWAY_INTERNAL_URL"), noGateway.getMessage());
    }
}
