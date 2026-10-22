package com.younglings.bot.moderation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModDurationsTest {
    @Test
    void readsMinutesHoursAndDaysAlone_orTogether() {
        assertEquals(30, ModDurations.parseMinutes("30m").getAsInt());
        assertEquals(120, ModDurations.parseMinutes("2h").getAsInt());
        assertEquals(7 * 24 * 60, ModDurations.parseMinutes("7d").getAsInt());
        assertEquals(24 * 60 + 12 * 60, ModDurations.parseMinutes("1d12h").getAsInt());
        assertEquals(90, ModDurations.parseMinutes(" 1H 30M ").getAsInt());
    }

    @Test
    void refusesAnythingThatIsNotADuration() {
        for (String bad : new String[]{null, "", " ", "abc", "10", "1w", "h", "1h x", "-5m", "0m", "1h-30m", "2.5h", "99999999d"}) {
            assertTrue(ModDurations.parseMinutes(bad).isEmpty(), "'" + bad + "'");
        }
    }

    @Test
    void wordsItBack() {
        assertEquals("45 minutes", ModDurations.words(45));
        assertEquals("1 hour", ModDurations.words(60));
        assertEquals("1 day 12 hours", ModDurations.words(36 * 60));
        assertEquals("28 days", ModDurations.words(ModDurations.MAX_TIMEOUT_MINUTES));
    }
}
