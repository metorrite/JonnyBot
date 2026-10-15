package com.younglings.bot.tracking;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyDigestLayoutTest {
    private static List<String> rows(int n) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) rows.add("`Name" + String.format("%-8s", i) + "` ✅ Visited  ⬜ Capped");
        return rows;
    }

    @Test
    void rowsSitTightTogetherOnePerLine() {
        String text = WeeklyDigestService.layoutRows(rows(68));
        assertEquals(68, text.lines().count());
        assertFalse(text.contains("-#"), "no spacer lines between rows");
        assertTrue(text.lines().noneMatch(String::isBlank), "no blank lines");
        assertTrue(text.length() <= WeeklyDigestService.ROWS_BUDGET);
    }

    @Test
    void aHugeClanIsCutWithACountInsteadOfFailingToSend() {
        String text = WeeklyDigestService.layoutRows(rows(200));
        assertTrue(text.length() <= WeeklyDigestService.ROWS_BUDGET, "" + text.length());
        long shown = text.lines().filter(l -> l.startsWith("`")).count();
        assertTrue(text.endsWith("…and " + (200 - shown) + " more"));
    }

    @Test
    void noRowsIsAnEmptyString() {
        assertEquals("", WeeklyDigestService.layoutRows(List.of()));
    }
}
