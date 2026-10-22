package com.younglings.bot.rsclans;

import com.younglings.bot.plugin.PluginEventSink;
import com.younglings.bot.plugin.PluginPayload;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The RSClans hub client: off by default, settings checked, and the feed relay passing hub events to the bot's own consumers. */
class RsClansHubClientTest {
    /** A key-shaped value made at runtime (secret scanners flag literal ones). */
    private static final String KEY = "rsh_" + "k".repeat(43);

    private static RsClansSettings on() {
        Map<String, String> env = Map.of("RSCLANS_HUB_ENABLED", "true", "RSCLANS_HUB_URL", "http://localhost:8090/", "RSCLANS_HUB_API_KEY", KEY);
        return RsClansSettings.from(env::get);
    }

    @Test
    void offUnlessSwitchedOnAndComplete() {
        assertFalse(RsClansSettings.from(k -> null).enabled());
        assertFalse(RsClansSettings.from(Map.of("RSCLANS_HUB_URL", "http://localhost:8090", "RSCLANS_HUB_API_KEY", KEY)::get).enabled());
        RsClansSettings bad = RsClansSettings.from(Map.of("RSCLANS_HUB_ENABLED", "true", "RSCLANS_HUB_URL", "http://example.org")::get);
        assertFalse(bad.enabled());
        assertTrue(bad.problem().contains("RSCLANS_HUB_URL") && bad.problem().contains("RSCLANS_HUB_API_KEY"));
        RsClansSettings good = on();
        assertTrue(good.enabled());
        assertEquals("http://localhost:8090", good.baseUrl());
        assertFalse(good.toString().contains(KEY));
    }

    @Test
    void offClientNeverCalls() {
        RsClansHubClient off = new RsClansHubClient(RsClansSettings.OFF, (m, p, b) -> {
            throw new AssertionError("called while off");
        });
        assertNull(off.call("GET", "/v1/me", null));
        assertFalse(off.putGuild(1L, "x"));
    }

    private static DataObject event(String id, String alias, String type) {
        DataObject data = DataObject.empty().put("id", id).put("alias", alias).put("rsn", "Zezima").put("type", type).put("category", "DROPS")
                .put("occurredAt", "2026-10-22T01:00:00Z").put("source", DataObject.empty().put("kind", "NPC").put("name", "Telos"))
                .put("data", DataObject.empty().put("itemName", "Dragon bones"));
        return DataObject.empty().put("id", id).put("kind", "plugin.event").put("alias", alias).put("data", data);
    }

    @Test
    void relayPassesEventsOfIdentityGrantsOnlyAndNeverReplaysOnFirstRun() {
        String known = "a1_" + "a".repeat(26);
        String anonymous = "a1_" + "b".repeat(26);
        List<String> paths = new ArrayList<>();
        AtomicReference<DataArray> nextPage = new AtomicReference<>(DataArray.empty().add(event("1", known, "DROP")));
        RsClansHubClient hub = new RsClansHubClient(on(), (method, path, body) -> {
            paths.add(method + " " + path);
            if (path.startsWith("/v1/feed")) {
                DataArray page = nextPage.getAndSet(DataArray.empty());
                return new RsClansHubClient.Answer(200, DataObject.empty().put("data", page).put("nextCursor", "c" + paths.size()).put("more", false));
            }
            if (path.equals("/v1/players/" + known)) return new RsClansHubClient.Answer(200, DataObject.empty().put("alias", known).put("discordId", "123456"));
            if (path.equals("/v1/players/" + anonymous)) return new RsClansHubClient.Answer(200, DataObject.empty().put("alias", anonymous));
            return new RsClansHubClient.Answer(404, DataObject.empty());
        });
        Map<String, String> store = new HashMap<>();
        List<String> received = new ArrayList<>();
        PluginEventSink sink = (userId, rsn, events) -> {
            for (PluginPayload.Event e : events) received.add(userId + " " + rsn + " " + e.type() + " " + e.key() + " " + e.sourceName());
        };
        List<Map.Entry<String, Long>> grants = new ArrayList<>();
        RsClansFeedPoller.Relay relay = new RsClansFeedPoller.Relay(hub, () -> Optional.ofNullable(store.get("c")), c -> store.put("c", c), List.of(sink), grants::add);

        // first run: skip to the end, nothing passed on
        assertEquals(0, relay.poll());
        assertTrue(received.isEmpty());
        assertNotNull(store.get("c"));

        nextPage.set(DataArray.empty().add(event("2", known, "DROP")).add(event("3", anonymous, "DROP"))
                .add(DataObject.empty().put("id", "4").put("kind", "player.grant_changed").put("alias", known)
                        .put("data", DataObject.empty().put("alias", known).put("revoked", false).put("identity", true))));
        assertEquals(1, relay.poll());
        assertEquals(List.of("123456 Zezima DROP hub:2 Telos"), received);
        assertEquals(List.of(Map.entry(known, 123456L)), grants);
        assertTrue(paths.stream().anyMatch(p -> p.contains("cursor=c")), "the stored cursor is sent");
    }

    @Test
    void hubEventsConvertLikeTheBotsOwn() {
        PluginPayload.Event e = RsClansFeedPoller.Relay.toEvent(event("9", "a1_x", "KILL").getObject("data"));
        assertNotNull(e);
        assertEquals("hub:9", e.key());
        assertEquals("NPC", e.sourceKind());
        assertNull(RsClansFeedPoller.Relay.toEvent(DataObject.empty().put("type", "NOPE")));
    }
}
