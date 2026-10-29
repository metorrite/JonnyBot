package com.runeclans.bot.internal;

import com.runeclans.bot.clansite.ClanSitePluginFeedRaceAccess;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The leaderboard race's real days (T-710): the month of clan points from the awards, and the hub's XP days as the website gets them. */
class LeaderboardRaceDaysTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private static SiteStatsRepository.PointsDay day(String rsn, int daysAgo, long points) {
        return new SiteStatsRepository.PointsDay(rsn, TODAY.minusDays(daysAgo), points);
    }

    @Test
    void pointsMonthRanksThePointsEarnedAndRunsTheirTotalsDayByDay() {
        SiteApi.PointsMonth month = SiteApi.pointsMonth(List.of(
                day("Early", 29, 50), day("Early", 10, 5),
                day("Late", 1, 40), day("late", 0, 30),       // the same member, however the name was cased
                day("Hidden", 5, 999),
                day("TooOld", 30, 500), day("Tomorrow", -1, 500),
                day("Nothing", 3, 0)), TODAY, rsn -> !rsn.equalsIgnoreCase("hidden"), 10);
        DataArray top = month.top();
        assertEquals(2, top.length());
        assertEquals("Late", top.getObject(0).getString("rsn"));
        assertEquals(70, top.getObject(0).getLong("value"));
        assertEquals("Early", top.getObject(1).getString("rsn"));
        assertEquals(55, top.getObject(1).getLong("value"));

        DataObject race = month.race();
        DataArray days = race.getArray("days");
        assertEquals(SiteApi.RACE_DAYS, days.length());
        assertEquals("2026-09-29", days.getString(0));
        assertEquals("2026-10-28", days.getString(29));
        DataArray early = race.getArray("rows").getObject(1).getArray("values");
        assertEquals(50, early.getLong(0));
        assertEquals(50, early.getLong(18));
        assertEquals(55, early.getLong(19));
        assertEquals(55, early.getLong(29));
        DataArray late = race.getArray("rows").getObject(0).getArray("values");
        assertEquals(0, late.getLong(27));
        assertEquals(40, late.getLong(28));
        assertEquals(70, late.getLong(29));
    }

    @Test
    void pointsMonthKeepsToTheLimitAndIsNullWhenNobodyEarned() {
        assertEquals(1, SiteApi.pointsMonth(List.of(day("A", 1, 5), day("B", 1, 4)), TODAY, rsn -> true, 1).top().length());
        SiteApi.PointsMonth none = SiteApi.pointsMonth(List.of(), TODAY, rsn -> true, 10);
        assertEquals(0, none.top().length());
        assertNull(none.race());
    }

    private static DataObject player(String rsn, long... cumulative) {
        DataArray values = DataArray.empty();
        for (long v : cumulative) values.add(v);
        return DataObject.empty().put("alias", "a1_x").put("rsn", rsn).put("cumulative", values);
    }

    @Test
    void xpDaysKeepNamedShownRowsThatLineUpWithTheDays() {
        DataObject answer = DataObject.empty().put("days", DataArray.fromCollection(List.of("2026-10-27", "2026-10-28")))
                .put("players", DataArray.empty()
                        .add(player("Zezima", 10, 30))
                        .add(player("Hidden", 5, 9))
                        .add(player(null, 1, 2))
                        .add(player("Short", 7))
                        .add(player("Nothing", 0, 0)));
        DataObject race = ClanSitePluginFeedRaceAccess.raceOf(answer, rsn -> !rsn.equalsIgnoreCase("hidden"));
        assertEquals(2, race.getArray("days").length());
        DataArray rows = race.getArray("rows");
        assertEquals(1, rows.length());
        assertEquals("Zezima", rows.getObject(0).getString("rsn"));
        assertEquals(30, rows.getObject(0).getArray("values").getLong(1));
        assertNull(ClanSitePluginFeedRaceAccess.raceOf(DataObject.empty().put("days", DataArray.fromCollection(List.of("2026-10-28")))
                .put("players", DataArray.empty().add(player("Zezima", 5))), rsn -> true), "one day isn't a race");
        assertNull(ClanSitePluginFeedRaceAccess.raceOf(DataObject.empty(), rsn -> true), "an answer without days");
        assertEquals(2, answer.getArray("players").getObject(0).getArray("cumulative").length(), "the hub's cached answer is left as it was");
    }

    @Test
    void xpDaysKeepTheTopTenTheClanMayShowOutOfTheHubsLongerList() {
        DataArray players = DataArray.empty().add(player("Alt", 900, 1000));
        for (int i = 0; i < 14; i++) players.add(player("Member" + i, 10, 100 - i));
        DataObject answer = DataObject.empty().put("days", DataArray.fromCollection(List.of("2026-10-27", "2026-10-28"))).put("players", players);
        DataArray rows = ClanSitePluginFeedRaceAccess.raceOf(answer, rsn -> rsn.startsWith("Member")).getArray("rows");
        assertEquals(10, rows.length(), "trimmed after the filter, so a hidden name doesn't cost a place");
        assertEquals("Member0", rows.getObject(0).getString("rsn"));
        assertEquals("Member9", rows.getObject(9).getString("rsn"));
    }
}
