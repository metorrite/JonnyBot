package com.younglings.bot.config;

import net.dv8tion.jda.api.requests.GatewayIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivilegedIntentsTest {
    private static final Set<GatewayIntent> WANTED = EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_PRESENCES);

    @AfterEach
    void reset() {
        PrivilegedIntents.filter(PrivilegedIntents.MESSAGE_CONTENT | PrivilegedIntents.GUILD_MEMBERS | PrivilegedIntents.PRESENCE, WANTED);
    }

    @Test
    void keepsEverythingTheApplicationHasSwitchedOn() {
        long all = (1L << 18) | (1L << 14) | (1L << 12);
        assertEquals(WANTED, PrivilegedIntents.filter(all, WANTED));
        assertTrue(PrivilegedIntents.messageContent());
    }

    @Test
    void limitedFlagsCountAsOn() {
        long limited = (1L << 19) | (1L << 15) | (1L << 13);
        assertEquals(WANTED, PrivilegedIntents.filter(limited, WANTED));
    }

    @Test
    void dropsOnlyTheIntentsThatAreOff() {
        Set<GatewayIntent> result = PrivilegedIntents.filter((1L << 14) | (1L << 12), WANTED);
        assertFalse(result.contains(GatewayIntent.MESSAGE_CONTENT));
        assertTrue(result.contains(GatewayIntent.GUILD_MEMBERS));
        assertTrue(result.contains(GatewayIntent.GUILD_MESSAGES));
        assertFalse(PrivilegedIntents.messageContent());
    }

    @Test
    void unknownFlagsLeaveTheRequestAlone() {
        assertEquals(WANTED, PrivilegedIntents.filter((Long) null, WANTED));
        assertEquals(262144L, PrivilegedIntents.parseFlags("{\"id\":\"1\",\"flags\": 262144,\"name\":\"x\"}"));
        assertNull(PrivilegedIntents.parseFlags("{\"id\":\"1\"}"));
    }
}
