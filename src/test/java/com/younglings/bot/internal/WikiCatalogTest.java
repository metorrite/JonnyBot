package com.younglings.bot.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiCatalogTest {
    private final WikiCatalog catalog = new WikiCatalog();

    @Test
    void theAdventureLogsSpellingsAllMeanTheSameBoss() {
        assertEquals("vindicta_gorvek", catalog.canonical("Vindicta").orElseThrow().key());
        assertEquals("the_gate_of_elidinis", catalog.canonical("Gate of Elidinis").orElseThrow().key());
        assertEquals("the_gate_of_elidinis", catalog.canonical("The Gate of Elidinis").orElseThrow().key());
        assertEquals("croesus", catalog.canonical("Croesus'").orElseThrow().key());
        assertEquals("the_magister", catalog.canonical("Magister").orElseThrow().key());
        assertTrue(catalog.canonical("mithril dragon").isEmpty(), "ordinary monsters are not bosses");
    }

    @Test
    void slugsAreStableKeys() {
        assertEquals("kril_tsutsaroth", WikiCatalog.slug("K'ril Tsutsaroth"));
        assertEquals("araxxis_eye", WikiCatalog.slug("Araxxi's eye"));
        assertEquals("godsword_shard_1", WikiCatalog.slug("Godsword shard 1"));
    }

    @Test
    void dropTablesLinkItemsToBosses() {
        assertTrue(catalog.bossesWithItem("torva_platelegs").stream().anyMatch(b -> b.key().equals("nex")));
        assertFalse(catalog.bosses().isEmpty());
        assertTrue(catalog.tracked("torva_platelegs"), "the adventure log reports Torva drops");
    }
}
