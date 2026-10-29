package com.runeclans.bot.leagues;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.runeclans.HubWriteQueue;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** /league standings and /league rounds (T-760): what the member is told from the hub's answers. */
class LeagueCommandTest {
    private static final long GUILD = Long.parseLong("2".repeat(18));
    private static final String LEAGUE = "0dc0dc00-0000-4000-8000-00000000000b";
    private static final BiFunction<String, Object[], String> TEXT = (key, args) -> key + Arrays.toString(args);

    private final Map<String, RuneClansHubClient.Read> answers = new HashMap<>();
    private final LeagueHub hub = LeagueHub.on(answers::get, (m, p, b) -> new HubWriteQueue.Result(HubWriteQueue.Outcome.REFUSED, null));

    private void guild(int status, DataObject json) {
        answers.put("/v1/guilds/" + GUILD + "/leagues", new RuneClansHubClient.Read(new RuneClansHubClient.Answer(status, json), false, false));
    }

    @Test
    void offNoClanNoLeagueAndAHubAwayEachHaveTheirLine() {
        assertEquals("league.off[]", LeagueCommand.reply(LeagueHub.off(), GUILD, true, null, TEXT).lines()[0]);
        assertEquals("league.try_again[]", LeagueCommand.reply(hub, GUILD, true, null, TEXT).lines()[0]);
        guild(404, DataObject.empty().put("error", "not_found"));
        assertEquals("league.no_clan[]", LeagueCommand.reply(hub, GUILD, true, null, TEXT).lines()[0]);
        guild(503, DataObject.empty());
        assertEquals(Containers.WARNING, LeagueCommand.reply(hub, GUILD, true, null, TEXT).accent());
        guild(200, DataObject.empty().put("leagues", DataArray.empty()));
        assertEquals("league.none[]", LeagueCommand.reply(hub, GUILD, false, null, TEXT).lines()[0]);
    }

    @Test
    void roundsComeFromTheGuildAnswerAndStandingsFromThePublicPage() {
        DataArray rounds = DataArray.empty().add(DataObject.empty().put("number", 1).put("name", "Boss night").put("status", "final"))
                .add(DataObject.empty().put("number", 2).put("name", "XP week").put("status", "running"));
        guild(200, DataObject.empty().put("leagues", DataArray.empty().add(DataObject.empty().put("id", LEAGUE).put("name", "Winter cup").put("status", "running").put("rounds", rounds))));
        String[] lines = LeagueCommand.reply(hub, GUILD, false, "https://runeclans.com", TEXT).lines();
        assertEquals("league.heading[Winter cup, running]", lines[0]);
        assertEquals("league.round[1, Boss night, final]", lines[1]);
        assertEquals("league.round[2, XP week, running]", lines[2]);
        assertEquals("league.page[https://runeclans.com/clans/leagues/" + LEAGUE + "]", lines[3]);
        assertEquals(4, lines.length);

        // standings: the public page isn't readable yet
        lines = LeagueCommand.reply(hub, GUILD, true, null, TEXT).lines();
        assertEquals("league.standings_unavailable[]", lines[1]);
        assertEquals(2, lines.length);
        answers.put("/v1/leagues/" + LEAGUE, new RuneClansHubClient.Read(new RuneClansHubClient.Answer(200, DataObject.empty().put("standings",
                DataArray.empty().add(DataObject.empty().put("rank", 1).put("name", "Alpha").put("points", 7.0))
                        .add(DataObject.empty().put("rank", 2).put("name", "Beta").put("points", 3.5)))), false, false));
        lines = LeagueCommand.reply(hub, GUILD, true, null, TEXT).lines();
        assertEquals("league.standing[1, Alpha, 7]", lines[1]);
        assertEquals("league.standing[2, Beta, 3.5]", lines[2]);
    }

    @Test
    void onlyAFewLeaguesAreShownAndTheRestCounted() {
        DataArray leagues = DataArray.empty();
        for (int i = 0; i < LeagueCommand.MAX_LEAGUES + 2; i++) leagues.add(DataObject.empty().put("id", LEAGUE).put("name", "L" + i).put("status", "running"));
        guild(200, DataObject.empty().put("leagues", leagues));
        String[] lines = LeagueCommand.reply(hub, GUILD, false, null, TEXT).lines();
        assertEquals("league.more[2]", lines[lines.length - 1]);
        assertEquals(LeagueCommand.MAX_LEAGUES * 2 + 1, lines.length, "a heading and a 'no rounds' line per league, then the count");
    }
}
