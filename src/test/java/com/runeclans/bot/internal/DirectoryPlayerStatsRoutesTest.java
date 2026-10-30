package com.runeclans.bot.internal;

import com.runeclans.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import net.dv8tion.jda.api.utils.data.DataArray;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** T-736: a community site's member stats, by name, from the bot's latest snapshots. */
class DirectoryPlayerStatsRoutesTest {
    @Test
    void namesAreCleanedOnceEachAndCapped() {
        assertEquals(List.of("Zezima", "Lynx Titan"), DirectoryPlayerStatsRoutes.names(" Zezima ,Lynx Titan,zezima,not a valid name!!,lynx titan"));
        assertNull(DirectoryPlayerStatsRoutes.names(null));
        assertNull(DirectoryPlayerStatsRoutes.names(",,!!"));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= DirectoryPlayerStatsRoutes.MAX_NAMES; i++) many.append("p").append(i).append(',');
        assertNull(DirectoryPlayerStatsRoutes.names(many.toString()));
    }

    @Test
    void onlyNamesWithASnapshotAreAnswered() {
        OffsetDateTime at = OffsetDateTime.of(2026, 10, 29, 12, 0, 0, 0, ZoneOffset.UTC);
        StatsSnapshotRow row = new StatsSnapshotRow(1, at, 2400, 500_000_000L, 138, 300, 1, 0);
        DataArray players = DirectoryPlayerStatsRoutes.answer(List.of("Zezima", "Never Polled"), names -> Map.of("zezima", row)).getArray("players");

        assertEquals(1, players.length());
        assertEquals("Zezima", players.getObject(0).getString("rsn"));
        assertEquals(2400, players.getObject(0).getInt("totalLevel"));
        assertEquals(500_000_000L, players.getObject(0).getLong("totalXp"));
        assertEquals("2026-10-29T12:00:00Z", players.getObject(0).getString("snapshotAt"));
    }
}
