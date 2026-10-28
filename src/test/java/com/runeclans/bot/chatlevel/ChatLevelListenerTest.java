package com.runeclans.bot.chatlevel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatLevelListenerTest {
    private static ChatLevelConfig config(Map<Long, Double> channels, Map<Long, Double> roles) {
        return new ChatLevelConfig(1, true, 15, 25, 60, false, null, null, false, Set.of(), Set.of(), channels, roles);
    }

    @Test
    void multipliersApplyPerChannelAndBestRole() {
        ChatLevelConfig c = config(Map.of(5L, 2.0), Map.of(10L, 1.5, 11L, 3.0));
        assertEquals(20, ChatLevelListener.xpFor(c, 1, List.of(), 20), "no multiplier");
        assertEquals(40, ChatLevelListener.xpFor(c, 5, List.of(), 20), "channel x2");
        assertEquals(60, ChatLevelListener.xpFor(c, 1, List.of(10L, 11L), 20), "best role x3");
        assertEquals(120, ChatLevelListener.xpFor(c, 5, List.of(11L), 20), "channel and role combine");
        assertEquals(0, ChatLevelListener.xpFor(config(Map.of(5L, 0.0), Map.of()), 5, List.of(), 20), "0 earns nothing");
    }

    @Test
    void anAwardIsOnlyReadyOncePerCooldownPerMember() {
        ChatLevelListener listener = new ChatLevelListener(null);
        assertTrue(listener.ready(1, 10, 60, 1_000));
        assertFalse(listener.ready(1, 10, 60, 30_000));
        assertTrue(listener.ready(1, 10, 60, 61_000));
        assertTrue(listener.ready(1, 11, 60, 2_000), "another member");
        assertTrue(listener.ready(2, 10, 60, 2_000), "another server");
    }

    @Test
    void levelRolesAreCumulativeOrReplaced() {
        Map<Integer, Long> rewards = new java.util.TreeMap<>(Map.of(5, 100L, 10, 200L, 20, 300L));
        assertEquals(List.of(), ChatLevelListener.rolesToHold(rewards, 4, false));
        assertEquals(List.of(100L, 200L), ChatLevelListener.rolesToHold(rewards, 12, false));
        assertEquals(List.of(200L), ChatLevelListener.rolesToHold(rewards, 12, true));
        assertEquals(List.of(100L), ChatLevelListener.rolesToHold(rewards, 5, true));
    }

    @Test
    void storedMultipliersRoundTripAndSkipJunk() {
        Map<Long, Double> read = ChatLevelConfig.readMultipliers("1:1.5,2:2.0,bad,3:x");
        assertEquals(Map.of(1L, 1.5, 2L, 2.0), read);
        assertEquals(read, ChatLevelConfig.readMultipliers(ChatLevelConfig.writeMultipliers(read)));
    }
}
