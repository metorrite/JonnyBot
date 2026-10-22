package com.younglings.bot.plugin;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the server adds as an event is stored: scaled XP, and drop values from Grand Exchange prices, never from the client. */
class PluginEnrichmentTest {
    private static PluginPayload.Event event(PluginEventType type, String json) {
        return new PluginPayload.Event("evt-12345678", type, Instant.parse("2026-10-11T12:00:00Z"), null, null, null, json);
    }

    private static DataObject data(PluginPayload.Event e) {
        return DataObject.fromJson(e.dataJson());
    }

    @Test
    void skillXpKeepsTheRawNumberAndGainsTheScaledOne() {
        var enrichment = new PluginEnrichment(name -> Optional.empty(), () -> 10L);

        DataObject d = data(enrichment.enrich(event(PluginEventType.XP, "{\"skill\":\"Mining\",\"xp\":1234567,\"level\":90}"), enrichment.budget()));

        assertEquals(1234567L, d.getLong("xp"));
        assertEquals(123456L, d.getLong("xpScaled"));
        assertEquals(10L, d.getLong("xpScale"));
    }

    @Test
    void theScaleIsConfigurableAndNeverBelowOne() {
        assertEquals(1234567L, data(new PluginEnrichment(n -> Optional.empty(), () -> 1L).enrich(event(PluginEventType.XP, "{\"skill\":\"A\",\"xp\":1234567,\"level\":1}"), new PluginEnrichment(n -> Optional.empty(), () -> 1L).budget())).getLong("xpScaled"));
        var zero = new PluginEnrichment(n -> Optional.empty(), () -> 0L);
        assertEquals(50L, data(zero.enrich(event(PluginEventType.XP, "{\"skill\":\"A\",\"xp\":50,\"level\":1}"), zero.budget())).getLong("xpScaled"));
    }

    @Test
    void aDropIsPricedFromTheExchangeAndTheClientsValueIsDiscarded() {
        var enrichment = new PluginEnrichment(name -> name.equals("Dragon bones") ? Optional.of(2_000L) : Optional.empty(), () -> 10L);

        DataObject d = data(enrichment.enrich(event(PluginEventType.DROP, "{\"itemName\":\"Dragon bones\",\"quantity\":3,\"value\":999999999}"), enrichment.budget()));

        assertEquals(2_000L, d.getLong("unitPriceGp"));
        assertEquals(6_000L, d.getLong("valueGp"));
        assertFalse(d.hasKey("value"), "a value the client sent is not kept");
    }

    @Test
    void aDropWithNoKnownPriceHasNoValueAtAll() {
        var enrichment = new PluginEnrichment(name -> Optional.empty(), () -> 10L);

        DataObject d = data(enrichment.enrich(event(PluginEventType.DROP, "{\"itemName\":\"Mystery\",\"quantity\":1,\"value\":5,\"valueGp\":5}"), enrichment.budget()));

        assertFalse(d.hasKey("valueGp"));
        assertFalse(d.hasKey("value"));
    }

    @Test
    void aHugeStackSaturatesInsteadOfWrapping() {
        var enrichment = new PluginEnrichment(name -> Optional.of(Long.MAX_VALUE / 2), () -> 10L);

        DataObject d = data(enrichment.enrich(event(PluginEventType.DROP, "{\"itemName\":\"x\",\"quantity\":10}"), enrichment.budget()));

        assertEquals(Long.MAX_VALUE, d.getLong("valueGp"));
    }

    @Test
    void pricesAreRememberedAndABatchAsksForAtMostEightNewItems() {
        List<String> asked = new ArrayList<>();
        var enrichment = new PluginEnrichment(name -> {
            asked.add(name);
            return Optional.of(10L);
        }, () -> 10L);
        var budget = enrichment.budget();

        enrichment.enrich(event(PluginEventType.DROP, "{\"itemName\":\"Same\",\"quantity\":1}"), budget);
        enrichment.enrich(event(PluginEventType.DROP, "{\"itemName\":\"same\",\"quantity\":1}"), budget);
        assertEquals(1, asked.size(), "the second drop of the same item reuses the price");

        for (int i = 0; i < 12; i++) enrichment.enrich(event(PluginEventType.DROP, "{\"itemName\":\"Item " + i + "\",\"quantity\":1}"), budget);
        assertEquals(PluginEnrichment.MAX_LOOKUPS_PER_BATCH, asked.size());

        DataObject beyond = data(enrichment.enrich(event(PluginEventType.DROP, "{\"itemName\":\"One more\",\"quantity\":1}"), budget));
        assertFalse(beyond.hasKey("valueGp"));
        assertTrue(data(enrichment.enrich(event(PluginEventType.DROP, "{\"itemName\":\"Same\",\"quantity\":2}"), budget)).hasKey("valueGp"), "a remembered price needs no lookup");
    }

    @Test
    void otherEventsAreLeftAlone() {
        var enrichment = new PluginEnrichment(name -> Optional.of(1L), () -> 10L);
        var kill = event(PluginEventType.KILL, "{\"bossName\":\"Telos\",\"killCount\":3}");

        assertEquals(kill, enrichment.enrich(kill, enrichment.budget()));
    }
}
