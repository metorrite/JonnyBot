package com.runeclans.bot.hubsites;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which hub feed entries become outbox jobs (T-734), and the server mappings that change with request handler roles. */
class RequestCardsTest {
    // fake ids, made at runtime
    private static final String GUILD = "2".repeat(18);
    private static final String CHANNEL = "6".repeat(18);
    private static final String ROLE = "7".repeat(18);
    private static final String MEMBER = "8".repeat(18);
    private static final String REQUEST = UUID.randomUUID().toString();

    private static DataObject entry(DataObject inner) {
        return DataObject.empty().put("item", "x").put("data", inner);
    }

    private static DataObject cardEntry(String channel) {
        DataObject inner = DataObject.empty().put("siteId", UUID.randomUUID().toString()).put("guildId", GUILD)
                .put("request", DataObject.empty().put("id", REQUEST).put("rev", 2).put("history", DataArray.empty()));
        if (channel != null) inner.put("channelId", channel);
        return entry(inner);
    }

    @Test
    void requestChangesBecomeOneCardJobPerRequest() {
        RequestCards.Job job = RequestCards.job("request.changed", cardEntry(CHANNEL));
        assertEquals(RequestCardJobs.CARD, job.kind());
        assertEquals(Long.parseLong(GUILD), job.guildId());
        assertEquals("request-card:" + REQUEST, job.dedupe(), "a newer change replaces a waiting one");
        assertEquals(CHANNEL, job.payload().getString("channelId"));
        assertEquals(REQUEST, job.payload().getObject("request").getString("id"));
        assertEquals(job.dedupe(), RequestCards.job("request.created", cardEntry(CHANNEL)).dedupe());
        assertFalse(RequestCards.job("request.changed", cardEntry(null)).payload().hasKey("channelId"), "no channel yet: the job says so");
    }

    @Test
    void memberRoleEntriesBecomeRoleJobs() {
        DataObject inner = DataObject.empty().put("guildId", GUILD).put("communityId", "k").put("roleId", ROLE).put("discordId", MEMBER).put("give", true);
        RequestCards.Job job = RequestCards.job("community.member.role", entry(inner));
        assertEquals(RequestCardJobs.ROLE, job.kind());
        assertTrue(job.payload().getBoolean("give"));
        assertEquals("community-role:k:" + MEMBER, job.dedupe(), "leaving soon after joining wins");
        assertNull(RequestCards.job("community.member.role", entry(inner.put("roleId", "@everyone"))));
    }

    @Test
    void anythingElseIsIgnored() {
        assertNull(RequestCards.job("site.roles_changed", cardEntry(CHANNEL)));
        assertNull(RequestCards.job("request.changed", null));
        assertNull(RequestCards.job("request.changed", DataObject.empty()));
        assertNull(RequestCards.job("request.changed", entry(DataObject.empty().put("guildId", "nope").put("request", DataObject.empty().put("id", REQUEST)))));
        assertNull(RequestCards.job("request.changed", entry(DataObject.empty().put("guildId", GUILD).put("request", DataObject.empty().put("id", "x")))));
    }

    @Test
    void handlerRoleChangesRemapTheServer() {
        DataObject feature = entry(DataObject.empty().put("guildId", GUILD).put("feature", DataObject.empty().put("key", "requests.discord")));
        assertEquals(Long.parseLong(GUILD), HubSiteReporter.remappedGuild("feature.changed", feature));
        DataObject other = entry(DataObject.empty().put("guildId", GUILD).put("feature", DataObject.empty().put("key", "discord.feed")));
        assertNull(HubSiteReporter.remappedGuild("feature.changed", other));
        assertEquals(Long.parseLong(GUILD), HubSiteReporter.remappedGuild("site.roles_changed", entry(DataObject.empty().put("guildId", GUILD))));
        assertNull(HubSiteReporter.remappedGuild("request.changed", feature));
    }
}
