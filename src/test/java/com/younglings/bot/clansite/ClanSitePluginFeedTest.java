package com.younglings.bot.clansite;

import com.younglings.bot.member.MemberProfileRepository;
import com.younglings.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The builder's activity widgets' feed (T-303): what is shown, what never is, and the cache. Made-up server id and names. */
class ClanSitePluginFeedTest {
    private static final long GUILD = 987654321031001L;

    private static DataObject event(String id, String type, String rsn, DataObject data) {
        return DataObject.empty().put("id", id).put("alias", "a1_" + "b".repeat(26)).put("rsn", rsn).put("type", type).put("category", "X")
                .put("occurredAt", "2026-10-22T20:00:00Z").put("data", data);
    }

    private static RuneClansHubClient hubWith(DataObject... events) {
        RuneClansHubClient hub = mock(RuneClansHubClient.class);
        when(hub.enabled()).thenReturn(true);
        DataArray data = DataArray.empty();
        for (DataObject e : events) data.add(e);
        when(hub.guildEvents(anyLong(), anyInt())).thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("data", data)));
        return hub;
    }

    private static MemberProfileRepository profiles(String hiddenRsn) {
        MemberProfileRepository profiles = mock(MemberProfileRepository.class);
        if (hiddenRsn != null) when(profiles.adventureLogHidden(eq(GUILD), eq(hiddenRsn))).thenReturn(true);
        return profiles;
    }

    @Test
    void eventsBecomePlainLinesInTheirView() {
        RuneClansHubClient hub = hubWith(
                event("5", "LEVEL_UP", "Skill Queen", DataObject.empty().put("skill", "Necromancy").put("level", 99)),
                event("4", "DROP", "Boss Basher", DataObject.empty().put("itemName", "Dragon rider lance").put("quantity", 1)),
                event("3", "CLUE", "Clue Hunter", DataObject.empty().put("tier", "ELITE").put("count", 3)),
                event("2", "KILL", "Boss Basher", DataObject.empty().put("bossName", "Rasial").put("killCount", 120)));
        ClanSitePluginFeed feed = new ClanSitePluginFeed(hub, profiles(null));

        DataObject activity = feed.feed(GUILD, "activity", 10);
        assertTrue(activity.getBoolean("available"));
        DataArray items = activity.getArray("items");
        assertEquals(2, items.length(), "level-up and clue; no drop or kill in activity");
        assertEquals("Skill Queen", items.getObject(0).getString("who"));
        assertEquals("reached level 99 Necromancy", items.getObject(0).getString("text"));
        assertEquals("level", items.getObject(0).getString("kind"));
        assertEquals("opened 3 elite clue caskets", items.getObject(1).getString("text"));

        DataArray drops = feed.feed(GUILD, "drops", 10).getArray("items");
        assertEquals("got a drop: Dragon rider lance", drops.getObject(0).getString("text"));
        DataArray all = feed.feed(GUILD, "runesync", 10).getArray("items");
        assertEquals(4, all.length());
        assertEquals("killed Rasial (kill 120)", all.getObject(3).getString("text"));

        verify(hub, times(1)).guildEvents(anyLong(), anyInt());
        assertNull(feed.feed(GUILD, "presence", 10), "an unknown view");
    }

    @Test
    void eachItemSaysWhetherTheMembersLinkWasConfirmed() {
        RuneClansHubClient hub = hubWith(
                event("2", "LEVEL_UP", "Skill Queen", DataObject.empty().put("skill", "Magic").put("level", 90)).put("verification", "public_data_corroborated"),
                event("1", "QUEST_COMPLETE", "Raid Caller", DataObject.empty().put("quest", "While Guthix Sleeps")).put("verification", "implicit"),
                event("0", "QUEST_COMPLETE", "Old Hub", DataObject.empty().put("quest", "Plague City")));
        DataArray items = new ClanSitePluginFeed(hub, profiles(null)).feed(GUILD, "activity", 10).getArray("items");
        assertTrue(items.getObject(0).getBoolean("confirmed"));
        assertFalse(items.getObject(1).getBoolean("confirmed"));
        assertFalse(items.getObject(2).getBoolean("confirmed"), "an event without the stamp counts as not confirmed");
    }

    @Test
    void onlineStatusAliasesAndHiddenMembersNeverShow() {
        RuneClansHubClient hub = hubWith(
                event("3", "PRESENCE", "Skill Queen", DataObject.empty().put("state", "ONLINE")),
                event("2", "LEVEL_UP", "Shy Player", DataObject.empty().put("skill", "Magic").put("level", 90)),
                event("1", "QUEST_COMPLETE", "Raid Caller", DataObject.empty().put("quest", "While Guthix Sleeps")));
        ClanSitePluginFeed feed = new ClanSitePluginFeed(hub, profiles("Shy Player"));
        DataObject runesync = feed.feed(GUILD, "runesync", 10);
        DataArray items = runesync.getArray("items");
        assertEquals(1, items.length());
        assertEquals("completed the quest While Guthix Sleeps", items.getObject(0).getString("text"));
        assertFalse(runesync.toString().contains("a1_"), "no alias leaves the bot");
    }

    @Test
    void noHubMeansNotAvailableAndAnUnknownServerIsEmpty() {
        RuneClansHubClient off = mock(RuneClansHubClient.class);
        when(off.enabled()).thenReturn(false);
        DataObject none = new ClanSitePluginFeed(off, profiles(null)).feed(GUILD, "activity", 5);
        assertFalse(none.getBoolean("available"));
        assertEquals(0, none.getArray("items").length());

        RuneClansHubClient unknown = mock(RuneClansHubClient.class);
        when(unknown.enabled()).thenReturn(true);
        when(unknown.guildEvents(anyLong(), anyInt())).thenReturn(new RuneClansHubClient.Answer(404, null));
        DataObject empty = new ClanSitePluginFeed(unknown, profiles(null)).feed(GUILD, "activity", 5);
        assertTrue(empty.getBoolean("available"));
        assertEquals(0, empty.getArray("items").length());
    }

    @Test
    void oddTextIsCutAndBadEventsSkipped() {
        assertEquals("x".repeat(80), ClanSitePluginFeed.clean("x".repeat(200)));
        assertEquals("a b", ClanSitePluginFeed.clean("  a \n\t b "));
        assertNull(ClanSitePluginFeed.text("LEVEL_UP", DataObject.empty()));
        assertNull(ClanSitePluginFeed.text("PRESENCE", DataObject.empty().put("state", "ONLINE")));
        assertEquals("got a drop: 1,000 x Coins", ClanSitePluginFeed.text("DROP", DataObject.empty().put("itemName", "Coins").put("quantity", 1000)));
    }
}
