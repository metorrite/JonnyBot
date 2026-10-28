package com.runeclans.bot.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RUNELINK_DM_SECRET, with the older RSNLINK_DM_SECRET still read when it is unset (T-659). */
class RuneLinkDmSecretSettingTest {
    private final List<String> warned = new ArrayList<>();

    private String read(Map<String, String> settings) {
        return BotConfig.renamedSettingFrom(BotConfig.RUNELINK_DM_SECRET_ENV, BotConfig.LEGACY_RUNELINK_DM_SECRET_ENV, settings::get,
                (legacy, name) -> warned.add(legacy + "->" + name));
    }

    @Test
    void theNewNameWinsAndNothingIsReported() {
        assertEquals("new", read(Map.of("RUNELINK_DM_SECRET", "new", "RSNLINK_DM_SECRET", "old")));
        assertTrue(warned.isEmpty(), "no warning when the new name is set: " + warned);
    }

    @Test
    void theOldNameAloneStillWorksAndIsReported() {
        assertEquals("old", read(Map.of("RSNLINK_DM_SECRET", "old")));
        assertEquals(List.of("RSNLINK_DM_SECRET->RUNELINK_DM_SECRET"), warned);
    }

    @Test
    void withNeitherItIsUnset() {
        assertNull(read(Map.of()));
        assertTrue(warned.isEmpty());
    }
}
