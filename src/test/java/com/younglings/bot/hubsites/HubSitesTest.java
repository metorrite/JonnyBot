package com.younglings.bot.hubsites;

import com.younglings.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SITES_BACKEND and the hub's answers about a server's site and its role mappings (T-505), against a stub hub. */
class HubSitesTest {
    // fake ids, made at runtime
    private static final long GUILD = Long.parseLong("2".repeat(18));
    private static final long ROLE = Long.parseLong("4".repeat(18));

    /** A stub hub: answers by path, or null (unreachable) when the path isn't set. */
    static final class Stub implements Function<String, RuneClansHubClient.Read> {
        final Map<String, RuneClansHubClient.Read> answers = new HashMap<>();
        final List<String> asked = new ArrayList<>();

        Stub answer(String path, int status, DataObject json) {
            answers.put(path, new RuneClansHubClient.Read(new RuneClansHubClient.Answer(status, json), false, false));
            return this;
        }

        Stub site(long guildId, String siteId) {
            return answer("/v1/guilds/" + guildId + "/site", 200,
                    DataObject.empty().put("siteId", siteId).put("slug", "younglings").put("published", true).put("guildId", Long.toString(guildId)));
        }

        @Override
        public RuneClansHubClient.Read apply(String path) {
            asked.add(path);
            return answers.get(path);
        }
    }

    @Test
    void onlyHubTurnsTheSwitchOn() {
        assertTrue(HubSites.switchOn("hub"));
        assertTrue(HubSites.switchOn(" HUB "));
        assertFalse(HubSites.switchOn(null));
        assertFalse(HubSites.switchOn("bot"));
        assertFalse(HubSites.switchOn("yes"));
    }

    @Test
    void offAsksTheHubNothing() {
        HubSites off = HubSites.off();
        assertFalse(off.movedToHub());
        assertFalse(off.readsHub());
        assertEquals(HubSites.Status.OFF, off.site(GUILD).status());
        assertEquals(Optional.empty(), off.mappedRoles(GUILD));
    }

    @Test
    void theSwitchWithoutTheHubClientMovesWritesButReadsNothing() {
        HubSites noClient = HubSites.on(null);
        assertTrue(noClient.movedToHub());
        assertFalse(noClient.readsHub());
        assertEquals(HubSites.Status.OFF, noClient.site(GUILD).status());
    }

    @Test
    void aServersSiteComesFromTheHub() {
        Stub hub = new Stub().site(GUILD, "site_abc");
        HubSites sites = HubSites.on(hub);
        HubSites.Lookup found = sites.site(GUILD);
        assertEquals(HubSites.Status.FOUND, found.status());
        assertEquals(new HubSites.HubSite("site_abc", "younglings", true, GUILD), found.site());
        assertEquals(Optional.of("site_abc"), sites.siteId(GUILD));
        assertEquals(List.of("/v1/guilds/" + GUILD + "/site", "/v1/guilds/" + GUILD + "/site"), hub.asked);
    }

    @Test
    void noSiteAndNoAnswerAreTold() {
        Stub hub = new Stub().answer("/v1/guilds/" + GUILD + "/site", 404, DataObject.empty().put("error", "not found"));
        assertEquals(HubSites.Status.NONE, HubSites.on(hub).site(GUILD).status());
        assertEquals(HubSites.Status.UNKNOWN, HubSites.on(new Stub()).site(GUILD).status(), "unreachable");
        Stub busy = new Stub().answer("/v1/guilds/" + GUILD + "/site", 503, null);
        assertEquals(HubSites.Status.UNKNOWN, HubSites.on(busy).site(GUILD).status());
        Stub odd = new Stub().answer("/v1/guilds/" + GUILD + "/site", 200, DataObject.empty().put("slug", "x"));
        assertEquals(HubSites.Status.UNKNOWN, HubSites.on(odd).site(GUILD).status(), "an answer with no site id isn't trusted");
    }

    @Test
    void roleMappingsAreTheRoleIds() {
        Stub hub = new Stub().answer("/v1/guilds/" + GUILD + "/role-mappings", 200,
                DataObject.empty().put("roleIds", DataArray.empty().add(Long.toString(ROLE)).add("not-a-role")));
        assertEquals(Optional.of(Set.of(ROLE)), HubSites.on(hub).mappedRoles(GUILD));
        assertEquals(Optional.empty(), HubSites.on(new Stub()).mappedRoles(GUILD), "the hub couldn't say");
        Stub none = new Stub().answer("/v1/guilds/" + GUILD + "/role-mappings", 404, null);
        assertEquals(Optional.of(Set.of()), HubSites.on(none).mappedRoles(GUILD));
    }

    @Test
    void aRoleReportNamesHoldersAsStrings() {
        assertEquals("{\"userIds\":[\"" + ROLE + "\"]}", HubSiteReporter.body(List.of(ROLE)).toString());
    }

    @Test
    void aSitesSettingsComeFromTheHubWhenItHasThem() {
        DataObject settings = DataObject.empty()
                .put("siteId", "site_abc").put("rev", 3)
                .put("online", DataObject.empty().put("mode", "names").put("limit", 40).put("groupByRole", false).put("maxLimit", 100))
                .put("feed", DataObject.empty().put("limit", 9).put("maxLimit", 25))
                .put("events", DataObject.empty().put("enabled", false));
        Stub hub = new Stub().answer("/v1/guilds/" + GUILD + "/site/settings", 200, settings);
        assertEquals(Optional.of(new HubSites.SiteSettings("names", 40, false, 9, false)), HubSites.on(hub).settings(GUILD));

        // no hub site, the hub down, the switch off or an incomplete answer: the bot keeps its own copy
        assertEquals(Optional.empty(), HubSites.on(new Stub().answer("/v1/guilds/" + GUILD + "/site/settings", 404, DataObject.empty())).settings(GUILD));
        assertEquals(Optional.empty(), HubSites.on(new Stub()).settings(GUILD));
        assertEquals(Optional.empty(), HubSites.off().settings(GUILD));
        assertEquals(Optional.empty(), HubSites.on(new Stub().answer("/v1/guilds/" + GUILD + "/site/settings", 200,
                DataObject.empty().put("online", DataObject.empty().put("mode", "names")))).settings(GUILD));
    }
}
