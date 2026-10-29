package com.runeclans.bot.leagues;

import com.runeclans.bot.runeclans.HubWriteQueue;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bot's league calls to the hub (T-759) against a stub: off asks nothing, reads by path, writes with the hub's bodies. */
class LeagueHubTest {
    private static final long GUILD = Long.parseLong("2".repeat(18));
    private static final String LEAGUE = "0dc0dc00-0000-4000-8000-00000000000b";

    static final class Stub implements Function<String, RuneClansHubClient.Read>, LeagueHub.Writer {
        final Map<String, RuneClansHubClient.Read> answers = new HashMap<>();
        final List<String> asked = new ArrayList<>();
        final List<String> written = new ArrayList<>();
        HubWriteQueue.Outcome outcome = HubWriteQueue.Outcome.SENT;

        Stub answer(String path, int status, DataObject json) {
            answers.put(path, new RuneClansHubClient.Read(new RuneClansHubClient.Answer(status, json), false, false));
            return this;
        }

        @Override
        public RuneClansHubClient.Read apply(String path) {
            asked.add(path);
            return answers.get(path);
        }

        @Override
        public HubWriteQueue.Result write(String method, String path, String body) {
            written.add(method + " " + path + " " + body);
            return new HubWriteQueue.Result(outcome, new RuneClansHubClient.Answer(outcome == HubWriteQueue.Outcome.SENT ? 200 : 503, DataObject.empty().put("message", "no")));
        }
    }

    @Test
    void offAsksNothingAndRefusesWrites() {
        LeagueHub off = LeagueHub.off();
        assertFalse(off.enabled());
        assertNull(off.guildLeagues(GUILD));
        assertTrue(off.clanIdOf(GUILD).isEmpty());
        assertEquals(HubWriteQueue.Outcome.REFUSED, off.attach(LEAGUE, 1, GUILD, 5L, "x", null).outcome());
        assertEquals(HubWriteQueue.Outcome.REFUSED, off.report(LEAGUE, 1, GUILD, DataArray.empty(), null).outcome());
    }

    @Test
    void readsComeByPathAndTheClanIdFromTheSiteRecord() {
        Stub stub = new Stub().answer("/v1/guilds/" + GUILD + "/leagues", 200, DataObject.empty().put("leagues", DataArray.empty()))
                .answer("/v1/guilds/" + GUILD + "/site", 200, DataObject.empty().put("siteId", "s1").put("clanId", "c1"));
        LeagueHub hub = LeagueHub.on(stub, stub);
        assertTrue(hub.enabled());
        assertEquals(200, hub.guildLeagues(GUILD).status());
        assertEquals("c1", hub.clanIdOf(GUILD).orElseThrow());
        assertTrue(hub.clanIdOf(GUILD + 1).isEmpty(), "unreachable: nothing known");
        assertNull(hub.guildLeagues(GUILD + 1));
        stub.answer("/v1/guilds/" + (GUILD + 2) + "/site", 200, DataObject.empty().put("siteId", "s2"));
        assertTrue(hub.clanIdOf(GUILD + 2).isEmpty(), "a community site has no clan");
        stub.answer("/v1/guilds/" + (GUILD + 3) + "/site", 404, DataObject.empty());
        assertTrue(hub.clanIdOf(GUILD + 3).isEmpty());
    }

    @Test
    void attachAndReportSendTheHubsBodies() {
        Stub stub = new Stub();
        LeagueHub hub = LeagueHub.on(stub, stub);
        assertEquals(HubWriteQueue.Outcome.SENT, hub.attach(LEAGUE, 2, GUILD, 77L, " Boss night ", "https://runeclans.com/e/77").outcome());
        String attach = stub.written.get(0);
        assertTrue(attach.startsWith("POST /v1/leagues/" + LEAGUE + "/rounds/2/event "), attach);
        DataObject body = DataObject.fromJson(attach.substring(attach.indexOf('{')));
        assertEquals(Long.toString(GUILD), body.getString("guildId"));
        assertEquals("77", body.getString("eventId"));
        assertEquals("Boss night", body.getString("name"));
        assertEquals("https://runeclans.com/e/77", body.getString("url"));

        hub.attach(LEAGUE, 2, GUILD, null, "ignored", "http://not-https");
        DataObject detach = DataObject.fromJson(stub.written.get(1).substring(stub.written.get(1).indexOf('{')));
        assertTrue(detach.isNull("eventId"));
        assertFalse(detach.hasKey("name"));
        assertFalse(detach.hasKey("url"));

        DataArray clans = DataArray.empty().add(DataObject.empty().put("clanId", "c1").put("raw", 340).put("participants", 2));
        hub.report(LEAGUE, 2, GUILD, clans, "x".repeat(600));
        String report = stub.written.get(2);
        assertTrue(report.startsWith("POST /v1/leagues/" + LEAGUE + "/rounds/2/results "), report);
        DataObject reported = DataObject.fromJson(report.substring(report.indexOf('{')));
        assertEquals(1, reported.getArray("clans").length());
        assertEquals(LeagueHub.MAX_NOTE, reported.getString("note").length(), "the note is cut to the hub's limit");

        stub.outcome = HubWriteQueue.Outcome.REFUSED;
        HubWriteQueue.Result refused = hub.report(LEAGUE, 2, GUILD, clans, null);
        assertEquals("no", LeagueHub.refusal(refused, "fallback"));
        assertEquals("fallback", LeagueHub.refusal(new HubWriteQueue.Result(HubWriteQueue.Outcome.REFUSED, null), "fallback"));
    }
}
