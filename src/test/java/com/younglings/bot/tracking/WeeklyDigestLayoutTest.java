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
    void aClanThatFitsGetsASmallGapBetweenRows() {
        String text = WeeklyDigestService.layoutRows(rows(68));
        assertEquals(67, text.lines().filter(WeeklyDigestService.SPACER::equals).count(), "a spacer between each pair of rows");
        assertTrue(text.length() <= WeeklyDigestService.ROWS_BUDGET, "fits the message: " + text.length());
        assertFalse(text.startsWith(WeeklyDigestService.SPACER));
        assertFalse(text.endsWith(WeeklyDigestService.SPACER));
    }

    @Test
    void aBigClanLosesTheGapsBeforeItLosesAnyone() {
        // the smallest clan whose list fits only without the gaps
        int n = 1;
        while (true) {
            var r = rows(n);
            int plain = r.stream().mapToInt(x -> x.length() + 1).sum();
            if (plain + (n - 1) * (WeeklyDigestService.SPACER.length() + 1) > WeeklyDigestService.ROWS_BUDGET) break;
            n++;
        }
        String text = WeeklyDigestService.layoutRows(rows(n));
        assertEquals(0, text.lines().filter(WeeklyDigestService.SPACER::equals).count());
        assertEquals(n, text.lines().count(), "nobody is cut");
        assertTrue(text.length() <= WeeklyDigestService.ROWS_BUDGET);
    }

    @Test
    void aHugeClanIsCutWithACountInsteadOfFailingToSend() {
        String text = WeeklyDigestService.layoutRows(rows(200));
        assertTrue(text.length() <= WeeklyDigestService.ROWS_BUDGET, "" + text.length());
        assertTrue(text.contains("…and "), text.substring(Math.max(0, text.length() - 60)));
        long shown = text.lines().filter(l -> l.startsWith("`")).count();
        assertTrue(text.endsWith("…and " + (200 - shown) + " more"));
    }

    @Test
    void noRowsIsAnEmptyString() {
        assertEquals("", WeeklyDigestService.layoutRows(List.of()));
    }
}
