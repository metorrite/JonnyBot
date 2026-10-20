package com.younglings.bot.clansite;

import com.younglings.bot.clansite.ClanSite.Template;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClanSiteTest {
    private static ClanSite site(boolean enabled, boolean published, String takedown) {
        return new ClanSite(1L, enabled, published, Template.HALL, "{}", "[]", 2L, Instant.EPOCH, Instant.EPOCH, takedown);
    }

    @Test
    void aNewServerHasNoSite() {
        ClanSite empty = ClanSite.empty(5L);
        assertFalse(empty.live());
        assertEquals(Template.CLASSIC, empty.template());
        assertFalse(ClanSiteService.servable(empty, true));
    }

    @Test
    void onlyAnEnabledPublishedSiteInTheBetaShows() {
        assertTrue(ClanSiteService.servable(site(true, true, null), true));
        assertFalse(ClanSiteService.servable(site(true, true, null), false), "not in the beta");
        assertFalse(ClanSiteService.servable(site(false, true, null), true), "switched off");
        assertFalse(ClanSiteService.servable(site(true, false, null), true), "a draft");
        assertFalse(ClanSiteService.servable(site(true, true, "Breaks the rules"), true), "taken down");
        assertTrue(ClanSiteService.servable(site(true, true, "  "), true), "a blank reason is no takedown");
        assertFalse(ClanSiteService.servable(null, true));
    }

    @Test
    void templatesParseLeniently() {
        assertEquals(Template.HALL, Template.parse(" hall ").orElseThrow());
        assertEquals(Template.MINIMAL, Template.parse("MINIMAL").orElseThrow());
        assertTrue(Template.parse("fancy").isEmpty());
        assertTrue(Template.parse(null).isEmpty());
    }
}
