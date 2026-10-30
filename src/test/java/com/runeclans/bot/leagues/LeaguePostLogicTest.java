package com.runeclans.bot.leagues;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which league feed entries become a post, their words and dedupe keys (T-760). */
class LeaguePostLogicTest {
    private static final String LEAGUE = "0dc0dc00-0000-4000-8000-00000000000b";

    private static DataObject entry(String event, Object... extra) {
        DataObject data = DataObject.empty().put("leagueId", LEAGUE).put("allianceId", "a1").put("name", "Winter cup").put("event", event);
        for (int i = 0; i + 1 < extra.length; i += 2) data.put((String) extra[i], extra[i + 1]);
        return DataObject.empty().put("item", "league").put("id", LEAGUE).put("rev", 3).put("data", data);
    }

    @Test
    void aNameAnotherClanTypedIsShownAsTypedNeverAsMarkdown() {
        DataObject entry = entry("proposed");
        entry.getObject("data").put("name", "[Claim your prize](https://evil.example) **now** ||@everyone||");
        String name = (String) LeaguePostLogic.plan("league.changed", entry).orElseThrow().args()[0];
        assertFalse(name.contains("[Claim your prize](https://evil.example)"), name);
        assertFalse(name.contains("**now**"), name);
        assertFalse(name.contains("@everyone"), name);
        // every markdown character is escaped, the brackets keep a masked link from forming, the ping is defanged
        assertEquals("\\[Claim your prize\\](https://evil.example) \\*\\*now\\*\\* \\|\\|@\u200Beveryone\\|\\|", name);
        assertEquals("Winter cup", LeaguePostLogic.plain("Winter cup"), "a plain name is left as it is");
        assertEquals("Winter cup # heading", LeaguePostLogic.plain("Winter cup\n# heading"), "a line break never starts a new line of the post");
        assertEquals("\\*\\*", LeaguePostLogic.plain("**"), "a lone bold marker is escaped too");
        assertEquals("a\\_b\\~c\\`d\\\\e", LeaguePostLogic.plain("a_b~c`d\\e"));
        assertEquals("", LeaguePostLogic.plain(null));
    }

    @Test
    void roundsResultsTermsAndTheLeagueItselfPostAndNoiseDoesNot() {
        LeaguePostLogic.Plan started = LeaguePostLogic.plan("league.round", entry("started", "number", 2)).orElseThrow();
        assertEquals("league.post.round_started", started.key());
        assertEquals("league-post:" + LEAGUE + ":league.round:started:2", started.dedupe());
        assertEquals(2, started.args()[0]);
        assertEquals("Winter cup", started.args()[1]);
        assertTrue(LeaguePostLogic.plan("league.round", entry("attached", "number", 2)).isEmpty(), "an event attached is the host's business");
        assertTrue(LeaguePostLogic.plan("league.round", entry("counting", "number", 2)).isEmpty());
        assertTrue(LeaguePostLogic.plan("league.round", entry("scheduled", "rounds", 6)).isEmpty());

        assertEquals("league.post.result_proposed", LeaguePostLogic.plan("league.results", entry("proposed", "number", 1)).orElseThrow().key());
        assertEquals("league.post.result_disputed", LeaguePostLogic.plan("league.results", entry("disputed", "number", 1)).orElseThrow().key());
        assertEquals("league.post.round_final", LeaguePostLogic.plan("league.results", entry("final", "number", 1)).orElseThrow().key());
        assertTrue(LeaguePostLogic.plan("league.results", entry("corrected", "number", 1)).isEmpty());
        assertTrue(LeaguePostLogic.plan("league.results", entry("confirmed", "number", 1)).isEmpty());

        LeaguePostLogic.Plan terms = LeaguePostLogic.plan("league.terms", entry("changed", "version", 2)).orElseThrow();
        assertEquals("league.post.terms_changed", terms.key());
        assertEquals(2, terms.args()[0]);
        assertEquals("league-post:" + LEAGUE + ":league.terms:changed:2", terms.dedupe());
        assertEquals("league.post.terms_agreed", LeaguePostLogic.plan("league.terms", entry("agreed", "version", 2)).orElseThrow().key());
        assertTrue(LeaguePostLogic.plan("league.terms", entry("accepted", "version", 2)).isEmpty(), "one clan's acceptance is not news");

        assertEquals("league.post.proposed", LeaguePostLogic.plan("league.changed", entry("proposed", "status", "proposed")).orElseThrow().key());
        assertEquals("league.post.running", LeaguePostLogic.plan("league.changed", entry("running", "status", "running")).orElseThrow().key());
        assertEquals("league.post.cancelled", LeaguePostLogic.plan("league.changed", entry("cancelled", "status", "cancelled")).orElseThrow().key());
        assertTrue(LeaguePostLogic.plan("league.changed", entry("scheduled", "status", "scheduled")).isEmpty());
    }

    @Test
    void malformedEntriesAndOtherKindsAreIgnored() {
        assertTrue(LeaguePostLogic.plan("site.changed", entry("started", "number", 1)).isEmpty());
        assertTrue(LeaguePostLogic.plan("league.round", null).isEmpty());
        assertTrue(LeaguePostLogic.plan("league.round", DataObject.empty()).isEmpty());
        DataObject noId = entry("started", "number", 1);
        noId.getObject("data").put("leagueId", "nope");
        assertTrue(LeaguePostLogic.plan("league.round", noId).isEmpty());
        assertTrue(LeaguePostLogic.plan("league.round", entry("started")).isEmpty(), "a round without a number");
        DataObject noName = entry("running", "status", "running");
        noName.getObject("data").remove("name");
        assertEquals("the league", LeaguePostLogic.plan("league.changed", noName).orElseThrow().args()[0]);
    }

    @Test
    void aPostIsForTheServersWhoseClanIsInTheLeague() {
        DataObject mine = DataObject.empty().put("leagues", DataArray.empty().add(DataObject.empty().put("id", LEAGUE.toUpperCase())));
        assertTrue(LeaguePostLogic.concerns(mine, LEAGUE));
        assertFalse(LeaguePostLogic.concerns(DataObject.empty().put("leagues", DataArray.empty()), LEAGUE));
        assertFalse(LeaguePostLogic.concerns(null, LEAGUE));
    }

    @Test
    void theTextLinksThePublicPageOrTheManagerForTermsToAnswer() {
        Optional<LeaguePostLogic.Plan> round = LeaguePostLogic.plan("league.round", entry("started", "number", 2));
        String text = LeaguePostLogic.text(round.orElseThrow(), "https://runeclans.com", (key, args) -> key + java.util.Arrays.toString(args));
        assertEquals("league.post.round_started[2, Winter cup]\nleague.post.page_link[https://runeclans.com/clans/leagues/" + LEAGUE + "]", text);
        LeaguePostLogic.Plan terms = LeaguePostLogic.plan("league.terms", entry("changed", "version", 2)).orElseThrow();
        assertTrue(LeaguePostLogic.text(terms, "https://runeclans.com", (key, args) -> key).endsWith("league.post.answer_link"));
        assertEquals("league.post.round_started", LeaguePostLogic.text(round.orElseThrow(), null, (key, args) -> key), "no site address, no link");
    }
}
