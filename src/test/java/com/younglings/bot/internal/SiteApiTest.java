package com.younglings.bot.internal;

import com.younglings.bot.runescape.PlayerLinkRepository.SkillHistoryPoint;
import com.younglings.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SiteApiTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-05T12:00:00Z");

    private static StatsSnapshotRow snapshot(int hoursAgo, long xp) {
        return new StatsSnapshotRow(1, NOW.minusHours(hoursAgo), 2000, xp, 120, 0, 0, 0);
    }

    @Test
    void gainUsesTheSnapshotAtOrBeforeTheWindowStart() {
        // newest first
        List<StatsSnapshotRow> rows = List.of(snapshot(0, 1_500), snapshot(10, 1_400), snapshot(30, 1_000), snapshot(60, 500));
        assertEquals(500, SiteApi.gainSince(rows, NOW.minusHours(24)), "baseline is the 30h-old snapshot, the newest one at or before 24h ago");
    }

    @Test
    void gainFallsBackToTheOldestSnapshotWhenHistoryIsShort() {
        List<StatsSnapshotRow> rows = List.of(snapshot(0, 1_500), snapshot(5, 1_200));
        assertEquals(300, SiteApi.gainSince(rows, NOW.minusDays(30)));
    }

    @Test
    void noGainWithFewerThanTwoSnapshotsOrIfXpDropped() {
        assertEquals(0, SiteApi.gainSince(List.of(), NOW.minusDays(1)));
        assertEquals(0, SiteApi.gainSince(List.of(snapshot(0, 100)), NOW.minusDays(1)));
        assertEquals(0, SiteApi.gainSince(List.of(snapshot(0, 100), snapshot(30, 900)), NOW.minusDays(1)));
    }

    @Test
    void skillGainsComeFromTheBaselineBeforeTheWindowAndSkipUnchangedSkills() {
        List<SkillHistoryPoint> points = List.of(
                new SkillHistoryPoint(0, NOW.minusHours(40), 1_000), // attack: baseline (before the 24h window)
                new SkillHistoryPoint(0, NOW.minusHours(10), 1_300),
                new SkillHistoryPoint(0, NOW, 1_800),
                new SkillHistoryPoint(1, NOW.minusHours(40), 500), // defence: unchanged
                new SkillHistoryPoint(1, NOW, 500));
        Map<Integer, Long> gains = SiteApi.skillGainsSince(points, NOW.minusHours(24));
        assertEquals(800, gains.get(0));
        assertNull(gains.get(1));
    }
}
