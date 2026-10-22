package com.younglings.bot.plugin;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginPayloadTest {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private static String event(String id, String type, String at, String data) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"at\":\"" + at + "\",\"data\":" + data + "}";
    }

    private static DataObject batch(String... events) {
        return DataObject.fromJson("{\"schema\":1,\"client\":{\"name\":\"jb-plugin\",\"version\":\"0.1.0\"},\"player\":{\"rsn\":\"Jonny Young\"},\"events\":[" + String.join(",", events) + "]}");
    }

    private static final String DROP_OK = "{\"itemName\":\"Crystal armour\",\"quantity\":1}";

    @Test
    void aGoodBatchIsAccepted() {
        var parsed = PluginPayload.parse(batch(event("drop-0001-a", "DROP", "2026-10-11T11:59:00Z", DROP_OK), event("kill-0001-a", "KILL", "2026-10-11T11:58:00Z", "{\"bossName\":\"Telos\",\"killCount\":12}")), NOW);

        assertNull(parsed.problem());
        assertEquals("Jonny Young", parsed.rsn());
        assertEquals("jb-plugin 0.1.0", parsed.client());
        assertEquals(2, parsed.events().size());
        assertTrue(parsed.rejects().isEmpty());
        assertEquals(PluginEventType.DROP, parsed.events().get(0).type());
    }

    @Test
    void theWholeBatchIsRefusedForTheWrongSchemaNameOrSize() {
        assertNotNull(PluginPayload.parse(DataObject.fromJson("{\"schema\":2,\"player\":{\"rsn\":\"A\"},\"events\":[{}]}"), NOW).problem());
        assertNotNull(PluginPayload.parse(DataObject.fromJson("{\"schema\":1,\"events\":[{}]}"), NOW).problem());
        assertNotNull(PluginPayload.parse(DataObject.fromJson("{\"schema\":1,\"player\":{\"rsn\":\"This name is far too long\"},\"events\":[{}]}"), NOW).problem());
        assertNotNull(PluginPayload.parse(DataObject.fromJson("{\"schema\":1,\"player\":{\"rsn\":\"A\"},\"events\":[]}"), NOW).problem());
        assertNotNull(PluginPayload.parse(DataObject.fromJson("{\"schema\":1,\"player\":{\"rsn\":\"A\"},\"events\":\"nope\"}"), NOW).problem());
    }

    @Test
    void tooManyEventsInOneBatchIsRefused() {
        String[] events = new String[PluginPayload.MAX_EVENTS + 1];
        for (int i = 0; i < events.length; i++) events[i] = event("evt-%05d".formatted(i), "PRESENCE", "2026-10-11T11:00:00Z", "{\"state\":\"ONLINE\"}");

        assertTrue(PluginPayload.parse(batch(events), NOW).problem().contains("at most"));
    }

    @Test
    void aBadEventIsRejectedOnItsOwnAndTheRestStillGoThrough() {
        var parsed = PluginPayload.parse(batch(event("ok-event-01", "DROP", "2026-10-11T11:59:00Z", DROP_OK), event("bad-type-01", "TELEPORT", "2026-10-11T11:59:00Z", "{}"),
                event("no-item-01", "DROP", "2026-10-11T11:59:00Z", "{\"quantity\":1}")), NOW);

        assertEquals(1, parsed.events().size());
        assertEquals(2, parsed.rejects().size());
        assertTrue(parsed.rejects().get(0).reason().startsWith("unknown_type"));
        assertTrue(parsed.rejects().get(1).reason().contains("itemName is required"));
    }

    @Test
    void idsMustBeUsableAsIdempotencyKeys() {
        var parsed = PluginPayload.parse(batch(event("short", "DROP", "2026-10-11T11:59:00Z", DROP_OK), event("has spaces in it", "DROP", "2026-10-11T11:59:00Z", DROP_OK)), NOW);

        assertTrue(parsed.events().isEmpty());
        assertTrue(parsed.rejects().stream().allMatch(r -> r.reason().startsWith("invalid_id")));
    }

    @Test
    void aRepeatedIdInsideOneBatchIsOnlyKeptOnce() {
        var parsed = PluginPayload.parse(batch(event("same-id-001", "DROP", "2026-10-11T11:59:00Z", DROP_OK), event("same-id-001", "DROP", "2026-10-11T11:59:00Z", DROP_OK)), NOW);

        assertEquals(1, parsed.events().size());
        assertEquals("duplicate_in_batch", parsed.rejects().get(0).reason());
    }

    @Test
    void timesMustBeRealAndRecent() {
        var parsed = PluginPayload.parse(batch(event("time-bad-01", "DROP", "yesterday", DROP_OK), event("time-old-01", "DROP", "2026-09-01T00:00:00Z", DROP_OK),
                event("time-fut-01", "DROP", "2026-10-11T12:30:00Z", DROP_OK), event("time-ok-001", "DROP", "2026-10-11T12:03:00Z", DROP_OK)), NOW);

        assertEquals(1, parsed.events().size(), "three minutes ahead is tolerated for clock drift");
        assertTrue(parsed.rejects().get(0).reason().startsWith("invalid_time"));
        assertTrue(parsed.rejects().get(1).reason().startsWith("too_old"));
        assertTrue(parsed.rejects().get(2).reason().startsWith("in_future"));
    }

    @Test
    void eachTypeChecksItsOwnFields() {
        assertNull(PluginEventType.LEVEL_UP.dataProblem(DataObject.fromJson("{\"skill\":\"Mining\",\"level\":99}")));
        assertNotNull(PluginEventType.LEVEL_UP.dataProblem(DataObject.fromJson("{\"skill\":\"Mining\",\"level\":0}")));
        assertNotNull(PluginEventType.LEVEL_UP.dataProblem(DataObject.fromJson("{\"skill\":\"Mining\",\"level\":999}")));
        assertNotNull(PluginEventType.LEVEL_UP.dataProblem(DataObject.fromJson("{\"level\":50}")));
        assertNull(PluginEventType.QUEST_COMPLETE.dataProblem(DataObject.fromJson("{\"quest\":\"Dragon Slayer\"}")));
        assertNotNull(PluginEventType.QUEST_COMPLETE.dataProblem(DataObject.fromJson("{\"quest\":\"\"}")));
        assertNull(PluginEventType.PRESENCE.dataProblem(DataObject.fromJson("{\"state\":\"online\"}")));
        assertNotNull(PluginEventType.PRESENCE.dataProblem(DataObject.fromJson("{\"state\":\"asleep\"}")));
        assertNotNull(PluginEventType.DROP.dataProblem(DataObject.fromJson("{\"itemName\":\"X\",\"quantity\":0}")));
        assertNotNull(PluginEventType.DROP.dataProblem(DataObject.fromJson("{\"itemName\":\"X\",\"value\":-5}")));
        assertNotNull(PluginEventType.DROP.dataProblem(DataObject.fromJson("{\"itemName\":\"X\",\"quantity\":1.5}")));
        assertNull(PluginEventType.DROP.dataProblem(DataObject.fromJson("{\"itemName\":\"X\",\"extra\":\"allowed\"}")));
    }

    @Test
    void eventTypesBelongToTheirCategories() {
        assertEquals(PluginCategory.DROPS, PluginEventType.DROP.category());
        assertEquals(PluginCategory.KILLS, PluginEventType.KILL.category());
        assertEquals(PluginCategory.ACHIEVEMENTS, PluginEventType.LEVEL_UP.category());
        assertEquals(PluginCategory.ACHIEVEMENTS, PluginEventType.COLLECTION_LOG.category());
        assertEquals(PluginCategory.PRESENCE, PluginEventType.PRESENCE.category());
        assertEquals(PluginEventType.DROP, PluginEventType.parse(" drop "));
        assertNull(PluginEventType.parse("nope"));
    }

    @Test
    void aSourceIsKeptAndItsKindChecked() {
        String good = "{\"id\":\"src-good-01\",\"type\":\"DROP\",\"at\":\"2026-10-11T11:59:00Z\",\"data\":" + DROP_OK + ",\"source\":{\"kind\":\"npc\",\"name\":\"Telos\",\"id\":1234}}";
        String bad = "{\"id\":\"src-bad-001\",\"type\":\"DROP\",\"at\":\"2026-10-11T11:59:00Z\",\"data\":" + DROP_OK + ",\"source\":{\"kind\":\"MAGIC\"}}";
        var parsed = PluginPayload.parse(batch(good, bad), NOW);

        assertEquals(1, parsed.events().size());
        assertEquals("NPC", parsed.events().get(0).sourceKind());
        assertEquals("Telos", parsed.events().get(0).sourceName());
        assertEquals(1234L, parsed.events().get(0).sourceId());
        assertTrue(parsed.rejects().get(0).reason().startsWith("invalid_source"));
    }

    @Test
    void namesAreNormalisedLikeRuneScapeNames() {
        assertEquals("Jonny Young", PluginPayload.normaliseRsn("Jonny Young"));
        assertEquals("Jonny Young", PluginPayload.normaliseRsn("  Jonny Young "));
        assertEquals("a_b-c 1", PluginPayload.normaliseRsn("a_b-c 1"));
        assertNull(PluginPayload.normaliseRsn(""));
        assertNull(PluginPayload.normaliseRsn("far too long a name"));
        assertNull(PluginPayload.normaliseRsn("bad!name"));
        assertNull(PluginPayload.normaliseRsn(null));
    }

    @Test
    void skillXpAndClueEventsAreChecked() {
        var good = PluginPayload.parse(batch(event("xp-event-01", "XP", "2026-10-11T11:59:00Z", "{\"skill\":\"Mining\",\"xp\":5600000000,\"level\":120}"),
                event("clue-evt-01", "clue", "2026-10-11T11:59:00Z", "{\"tier\":\"hard\",\"count\":57}")), NOW);

        assertEquals(2, good.events().size());
        assertEquals(PluginCategory.XP, good.events().get(0).type().category());
        assertEquals(PluginCategory.ACHIEVEMENTS, good.events().get(1).type().category());

        var bad = PluginPayload.parse(batch(event("xp-nolvl-01", "XP", "2026-10-11T11:59:00Z", "{\"skill\":\"Mining\",\"xp\":10}"),
                event("xp-neg-001", "XP", "2026-10-11T11:59:00Z", "{\"skill\":\"Mining\",\"xp\":-1,\"level\":5}"),
                event("xp-lvl-001", "XP", "2026-10-11T11:59:00Z", "{\"skill\":\"Mining\",\"xp\":1,\"level\":999}"),
                event("xp-skill-01", "XP", "2026-10-11T11:59:00Z", "{\"xp\":1,\"level\":5}"),
                event("clue-zero-1", "CLUE", "2026-10-11T11:59:00Z", "{\"tier\":\"hard\",\"count\":0}"),
                event("clue-tier-1", "CLUE", "2026-10-11T11:59:00Z", "{\"count\":3}")), NOW);

        assertEquals(0, bad.events().size());
        assertEquals(6, bad.rejects().size());
    }
}
