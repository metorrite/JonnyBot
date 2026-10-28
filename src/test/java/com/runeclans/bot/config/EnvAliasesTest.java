package com.runeclans.bot.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvAliasesTest {
    private final Set<String> warned = new HashSet<>();
    private final List<String> warnings = new ArrayList<>();

    private String resolve(Map<String, String> env) {
        return EnvAliases.resolve("RUNECLAN_BOT_X", "JONNYBOT_X", env::get, warned, (n, o) -> warnings.add(o + "->" + n));
    }

    @Test
    void theNewNameWins() {
        assertEquals("new", resolve(Map.of("RUNECLAN_BOT_X", "new", "JONNYBOT_X", "old")));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void theOldNameIsUsedWhenTheNewOneIsMissing() {
        assertEquals("old", resolve(Map.of("JONNYBOT_X", "old")));
        assertEquals(List.of("JONNYBOT_X->RUNECLAN_BOT_X"), warnings);
    }

    @Test
    void aBlankNewNameCountsAsUnset() {
        assertEquals("old", resolve(Map.of("RUNECLAN_BOT_X", "  ", "JONNYBOT_X", "old")));
    }

    @Test
    void theDeprecationIsLoggedOnce() {
        for (int i = 0; i < 3; i++) assertEquals("old", resolve(Map.of("JONNYBOT_X", "old")));
        assertEquals(1, warnings.size());
    }

    @Test
    void neitherSetIsNullAndQuiet() {
        assertNull(resolve(Map.of()));
        assertNull(resolve(Map.of("JONNYBOT_X", "")));
        assertTrue(warnings.isEmpty());
    }

}
