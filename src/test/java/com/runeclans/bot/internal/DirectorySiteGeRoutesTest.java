package com.runeclans.bot.internal;

import com.runeclans.bot.lookup.GePriceService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The GE price watch route's rules (T-592): which names are asked for, and what each item answers. */
class DirectorySiteGeRoutesTest {
    @Test
    void namesAreTrimmedDeduplicatedAndCapped() {
        assertEquals(List.of("Dragon bones", "Rune bar"), DirectorySiteGeRoutes.names(" Dragon bones | | dragon BONES|Rune bar"));
        assertEquals(List.of(), DirectorySiteGeRoutes.names(null));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 20; i++) many.append("Item ").append(i).append('|');
        assertEquals(DirectorySiteGeRoutes.MAX_ITEMS, DirectorySiteGeRoutes.names(many.toString()).size());
        assertTrue(DirectorySiteGeRoutes.names("x".repeat(81)).isEmpty());
    }

    @Test
    void anItemAnswersItsPriceChangeAndWeek() {
        var price = new GePriceService.GePrice("Dragon bones", "536", 2_500, 10, Instant.EPOCH, new GePriceService.Trend(7, 2_000, 25.04), null);
        DataObject found = DirectorySiteGeRoutes.json("dragon bones", Optional.of(new GePriceService.Watched(price, List.of(2_000L, 2_500L))));
        assertTrue(found.getBoolean("found"));
        assertEquals("Dragon bones", found.getString("name"));
        assertEquals(2_500, found.getLong("price"));
        assertEquals(25.0, found.getDouble("change7d"));
        assertEquals(2, found.getArray("week").length());
        assertEquals("https://runescape.wiki/w/Dragon_bones", found.getString("wiki"));
        DataObject missing = DirectorySiteGeRoutes.json("Nothing", Optional.empty());
        assertFalse(missing.getBoolean("found"));
        assertEquals("Nothing", missing.getString("name"));
    }
}
