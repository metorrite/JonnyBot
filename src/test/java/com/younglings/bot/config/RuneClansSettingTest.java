package com.younglings.bot.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The RuneClans hub client's settings: RUNECLANS_* names, with the pre-rename RSCLANS_* names still read for one release (T-284). */
class RuneClansSettingTest {
    private final List<String> warned = new ArrayList<>();

    private String read(String name, Map<String, String> settings) {
        return BotConfig.runeClansSettingFrom(name, settings::get, warned::add);
    }

    @Test
    void theNewNameWinsAndTheOldOneIsNotEvenMentioned() {
        assertEquals("https://hub.example", read("RUNECLANS_HUB_URL", Map.of("RUNECLANS_HUB_URL", "https://hub.example", "RSCLANS_HUB_URL", "https://old.example")));
        assertTrue(warned.isEmpty(), "no warning when the new name is set: " + warned);
    }

    @Test
    void theOldNameAloneIsUsedAndReportedSoTheBotWarns() {
        assertEquals("true", read("RUNECLANS_HUB_ENABLED", Map.of("RSCLANS_HUB_ENABLED", "true")));
        assertEquals(List.of("RSCLANS_HUB_ENABLED"), warned);
    }

    @Test
    void withNeitherItIsUnsetAndNothingIsReported() {
        assertNull(read("RUNECLANS_HUB_API_KEY", Map.of()));
        assertTrue(warned.isEmpty());
    }

    @Test
    void onlyRuneClansSettingsAreServed() {
        assertThrows(IllegalArgumentException.class, () -> read("DATABASE_URL", Map.of()));
    }
}
