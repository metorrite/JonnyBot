package com.runeclans.bot.hubsites;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunityCommandTest {
    private static final long GUILD = Long.parseLong("2".repeat(18));
    private static final String USER = "3".repeat(18);
    private static final String COMMUNITY = "00000000-0000-4000-8000-000000000001";
    private static final String SITES = "https://runeclans.example";
    /** The key and arguments instead of the words, so the tests don't depend on the wording. */
    private static final BiFunction<String, Object[], String> KEYS = (key, args) -> key + Arrays.toString(args);

    private static HubSites sites(String communityId) {
        DataObject site = DataObject.empty().put("siteId", "site_abc").put("slug", "younglings").put("published", true)
                .put("guildId", String.valueOf(GUILD)).put("communityId", communityId);
        return HubSites.on(path -> new RuneClansHubClient.Read(new RuneClansHubClient.Answer(200, site), false, false));
    }

    private static DataObject state(String state) {
        return DataObject.empty().put("name", "Younglings").put("me", DataObject.empty().put("state", state));
    }

    private static DataObject refusal(String code, String message) {
        return DataObject.empty().put("error", code).put("message", message);
    }

    /** Records each hub call and answers with {@code answer}. */
    private static final class Hub implements CommunityCommand.Call {
        final List<String> calls = new ArrayList<>();
        final RuneClansHubClient.Answer answer;

        Hub(int status, DataObject json) {
            this.answer = new RuneClansHubClient.Answer(status, json);
        }

        @Override
        public RuneClansHubClient.Answer call(String method, String path, String body) {
            calls.add(method + " " + path + (body == null ? "" : " " + body));
            return answer;
        }
    }

    @Test
    void joiningAnOpenCommunity() {
        Hub hub = new Hub(200, state("member"));
        CommunityCommand.Reply r = CommunityCommand.reply(sites(COMMUNITY), hub, SITES, GUILD, USER, true, KEYS);
        assertEquals(Containers.SUCCESS, r.accent());
        assertEquals("community.joined[Younglings]", r.lines()[0]);
        assertEquals(List.of("POST /v1/communities/" + COMMUNITY + "/members {\"discordId\":\"" + USER + "\"}"), hub.calls);
    }

    @Test
    void leavingCallsDeleteWithTheCaller() {
        Hub hub = new Hub(200, state("none"));
        CommunityCommand.Reply r = CommunityCommand.reply(sites(COMMUNITY), hub, SITES, GUILD, USER, false, KEYS);
        assertEquals("community.left[Younglings]", r.lines()[0]);
        assertEquals(List.of("DELETE /v1/communities/" + COMMUNITY + "/members/" + USER), hub.calls);
    }

    @Test
    void aRequestCommunityPointsAtTheSitesJoinForm() {
        Hub hub = new Hub(409, refusal("use_site", "Joining needs the join form on the website."));
        CommunityCommand.Reply r = CommunityCommand.reply(sites(COMMUNITY), hub, SITES, GUILD, USER, true, KEYS);
        assertEquals("Joining needs the join form on the website.", r.lines()[0]);
        assertEquals("community.join_form[https://younglings.runeclans.example/join]", r.lines()[1]);
    }

    @Test
    void aMissingLinkPointsAtTheLinkPage() {
        Hub hub = new Hub(403, refusal("link_needed", "Link your RuneScape character with RuneLink first, then try again."));
        CommunityCommand.Reply r = CommunityCommand.reply(sites(COMMUNITY), hub, SITES, GUILD, USER, true, KEYS);
        assertEquals("community.link_page[" + SITES + "/plugins/link]", r.lines()[1]);
    }

    @Test
    void otherRefusalsShowTheHubsWords() {
        Hub hub = new Hub(409, refusal("closed", "This community isn't taking new members."));
        CommunityCommand.Reply r = CommunityCommand.reply(sites(COMMUNITY), hub, SITES, GUILD, USER, true, KEYS);
        assertEquals(Containers.WARNING, r.accent());
        assertEquals(List.of("This community isn't taking new members."), List.of(r.lines()));
    }

    @Test
    void noCommunityOrNoHubAnswerCallsNothingOrSaysTryAgain() {
        Hub hub = new Hub(200, state("member"));
        assertEquals("community.no_community[]", CommunityCommand.reply(sites(null), hub, SITES, GUILD, USER, true, KEYS).lines()[0]);
        assertEquals("community.no_community[]", CommunityCommand.reply(HubSites.off(), hub, SITES, GUILD, USER, true, KEYS).lines()[0]);
        assertTrue(hub.calls.isEmpty());
        Hub down = new Hub(503, null);
        assertEquals("community.try_again[]", CommunityCommand.reply(sites(COMMUNITY), down, SITES, GUILD, USER, true, KEYS).lines()[0]);
    }

    @Test
    void theJoinPageIsOnTheSitesOwnAddress() {
        assertEquals("http://younglings.lvh.me:3200/join", CommunityCommand.joinPage("http://lvh.me:3200", "younglings"));
        assertNull(CommunityCommand.joinPage(null, "younglings"));
        assertNull(CommunityCommand.joinPage(SITES, "Not A Slug"));
    }
}
