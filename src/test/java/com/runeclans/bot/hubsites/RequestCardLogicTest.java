package com.runeclans.bot.hubsites;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Request cards (T-734): what a card shows, which buttons it offers, and how a press and the hub's answer are worded. */
class RequestCardLogicTest {
    // fake ids, made at runtime
    private static final String PERSON = "4".repeat(18);
    private static final String ADMIN = "5".repeat(18);
    private static final String ID = UUID.randomUUID().toString();
    /** The key and its arguments, so tests see what was asked for. */
    private static final BiFunction<String, Object[], String> TEXT = (key, args) -> args.length == 0 ? key : key + Arrays.toString(args);

    private static DataObject request(String kind, String status, DataArray history) {
        return DataObject.empty().put("id", ID).put("kind", kind).put("status", status).put("rev", 3).put("discordId", PERSON).put("name", "Zezima")
                .put("characters", DataArray.empty().add("Zezima")).put("history", history).putNull("assignee").put("createdAt", "2026-10-29T10:00:00Z")
                .put("answers", DataArray.empty().add(DataObject.empty().put("id", "q1").put("label", "Why join?").put("value", "To **skill** with @everyone")));
    }

    private static DataObject change(String status, String where) {
        return DataObject.empty().put("event", "status").put("status", status).put("where", where);
    }

    private static List<String> actions(RequestCardLogic.Card card) {
        return card.buttons().stream().map(b -> RequestCardLogic.parse(b.id()).action()).toList();
    }

    @Test
    void aNewApplicationOffersTheWebsitesStepsAndClaim() {
        RequestCardLogic.Card card = RequestCardLogic.card(request("application", "new", DataArray.empty()), TEXT);
        assertEquals(List.of("accepted", "declined", "in_progress", "closed", "claim"), actions(card));
        assertEquals(Containers.INFO, card.accent());
        assertEquals("requests.from[<@" + PERSON + ">] (Zezima)", card.lines().get(1));
        String all = String.join("\n", card.lines());
        assertTrue(all.contains("\\*\\*skill\\*\\*"), "the person's own markdown is shown as typed");
        assertFalse(all.contains("@everyone"), "nobody's @ survives as a mention: " + all);
        assertTrue(card.lines().get(card.lines().size() - 1).contains("<t:"));
        RequestCardLogic.Press accept = RequestCardLogic.parse(card.buttons().get(0).id());
        assertEquals(ID, accept.requestId());
        assertEquals(3, accept.rev());
    }

    @Test
    void joinRequestsFollowTheBoardsRules() {
        DataArray accepted = DataArray.empty().add(change("accepted", "site"));
        assertEquals(List.of("closed"), actions(RequestCardLogic.card(request("join", "accepted", accepted), TEXT)));
        assertEquals(List.of("closed", "new"), actions(RequestCardLogic.card(request("application", "accepted", accepted), TEXT)));
        DataArray left = DataArray.empty().add(change("closed", "hub"));
        assertEquals(List.of(), actions(RequestCardLogic.card(request("join", "closed", left), TEXT)), "withdrawn: nothing to press");
        assertEquals(List.of("new"), actions(RequestCardLogic.card(request("join", "closed", DataArray.empty().add(change("closed", "site"))), TEXT)));
        assertEquals(List.of(), actions(RequestCardLogic.card(request("join", "closed", DataArray.empty()), TEXT)), "closed before the history: withdrawn");
        assertEquals("requests.accept_member", RequestCardLogic.card(request("join", "new", DataArray.empty()), TEXT).buttons().get(0).label());
    }

    @Test
    void aClaimedRequestOffersLetGoAndSaysWho() {
        DataObject r = request("application", "in_progress", DataArray.empty()).put("assignee", DataObject.empty().put("discordId", ADMIN));
        RequestCardLogic.Card card = RequestCardLogic.card(r, TEXT);
        assertEquals("unclaim", actions(card).get(actions(card).size() - 1));
        assertTrue(card.lines().contains("requests.claimed_by[<@" + ADMIN + ">]"));
        assertEquals(List.of("accepted", "declined", "new", "closed", "unclaim"), actions(card));
    }

    @Test
    void longAnswersStopBeforeDiscordsLimit() {
        DataArray answers = DataArray.empty();
        for (int i = 0; i < 10; i++) answers.add(DataObject.empty().put("label", "Q" + i).put("value", "x".repeat(600)));
        RequestCardLogic.Card card = RequestCardLogic.card(request("application", "new", DataArray.empty()).put("answers", answers), TEXT);
        assertTrue(String.join("\n", card.lines()).length() < 4000);
        assertTrue(card.lines().contains("requests.more_on_site"));
    }

    @Test
    void buttonIdsAreParsedStrictly() {
        assertEquals("claim", RequestCardLogic.parse(RequestCardLogic.buttonId("claim", ID, 7)).action());
        assertNull(RequestCardLogic.parse("rcreq:delete:" + ID + ":1"));
        assertNull(RequestCardLogic.parse("rcreq:accepted:not-an-id:1"));
        assertNull(RequestCardLogic.parse("rcreq:accepted:" + ID + ":x"));
        assertNull(RequestCardLogic.parse("other:accepted:" + ID + ":1"));
        assertNull(RequestCardLogic.parse(null));
    }

    @Test
    void aPressNamesTheChangeAndWhoPressed() {
        RequestCardLogic.Press accept = RequestCardLogic.parse(RequestCardLogic.buttonId("accepted", ID, 2));
        assertEquals("/v1/requests/" + ID + "/status", RequestCardLogic.pressPath(accept));
        DataObject body = RequestCardLogic.pressBody(accept, ADMIN, true, false);
        assertEquals("accepted", body.getString("status"));
        assertEquals(ADMIN, body.getObject("by").getString("discordId"));
        assertTrue(body.getObject("by").getBoolean("administrator"));
        assertFalse(body.getObject("by").getBoolean("owner"));

        RequestCardLogic.Press claim = RequestCardLogic.parse(RequestCardLogic.buttonId("claim", ID, 2));
        assertEquals("/v1/requests/" + ID + "/assignee", RequestCardLogic.pressPath(claim));
        assertEquals("me", RequestCardLogic.pressBody(claim, ADMIN, false, false).getString("assignee"));
        DataObject letGo = RequestCardLogic.pressBody(RequestCardLogic.parse(RequestCardLogic.buttonId("unclaim", ID, 2)), ADMIN, false, false);
        assertTrue(letGo.hasKey("assignee") && letGo.isNull("assignee"));
    }

    @Test
    void theHubsAnswerIsWordedForThePresser() {
        DataObject now = request("application", "accepted", DataArray.empty());
        RequestCardLogic.Outcome ok = RequestCardLogic.answered(new RuneClansHubClient.Answer(200, now));
        assertEquals(now, ok.card());
        assertNull(ok.noteKey());

        RequestCardLogic.Outcome stale = RequestCardLogic.answered(new RuneClansHubClient.Answer(409,
                DataObject.empty().put("error", "changed_elsewhere").put("rev", 4).put("current", now)));
        assertEquals(now, stale.card(), "the card shows the request as it is now");
        assertEquals("requests.changed_elsewhere", stale.noteKey());

        assertEquals("requests.not_allowed", RequestCardLogic.answered(new RuneClansHubClient.Answer(403, DataObject.empty())).noteKey());
        assertNull(RequestCardLogic.answered(new RuneClansHubClient.Answer(403, DataObject.empty())).card());
        assertEquals("requests.not_here", RequestCardLogic.answered(new RuneClansHubClient.Answer(404, DataObject.empty())).noteKey());
        assertEquals("requests.try_again", RequestCardLogic.answered(null).noteKey());
        assertEquals("requests.try_again", RequestCardLogic.answered(new RuneClansHubClient.Answer(503, null)).noteKey());
        assertEquals("requests.try_again", RequestCardLogic.answered(new RuneClansHubClient.Answer(429, null, 5)).noteKey());
        RequestCardLogic.Outcome bad = RequestCardLogic.answered(new RuneClansHubClient.Answer(400,
                DataObject.empty().put("error", "invalid").put("message", "A join request the person withdrew stays closed.")));
        assertEquals("A join request the person withdrew stays closed.", bad.hubMessage());
    }
}
