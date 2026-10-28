package com.runeclans.bot.internal;

import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.clanprofile.SlugChangeRules;
import com.runeclans.bot.clanprofile.SlugChangeService;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The site admin's address routes: what each call asks of the service, and what the page gets back. */
class SiteAddressRoutesTest {
    private static final long G = Long.parseLong("5".repeat(18));
    private final SlugChangeService changes = mock(SlugChangeService.class);
    private final ClanProfileService profiles = mock(ClanProfileService.class);
    private final SiteAddressRoutes routes = new SiteAddressRoutes(changes, profiles);

    SiteAddressRoutesTest() {
        ClanProfile p = new ClanProfile(G, ClanProfile.Visibility.PUBLIC, "now-slug", "Clan", "", "", null, null, null, Set.of(), ClanProfile.Recruiting.CLOSED, "",
                null, false, null, null, null, null, null, null);
        when(profiles.profileOf(G)).thenReturn(p);
    }

    @Test
    void aRequestAnswersWithThePendingAddressAndItsTimes() {
        Instant at = Instant.parse("2026-01-01T10:00:00Z");
        when(changes.request(G, "new-slug")).thenReturn(new SlugChangeService.Outcome(null));
        when(changes.status(G)).thenReturn(new SlugChangeRules.Status("new-slug", at, at.plusSeconds(3600), null));
        DataObject answer = routes.route(G, "POST", "", () -> DataObject.empty().put("slug", "new-slug"));
        assertEquals("now-slug", answer.getString("slug"));
        assertEquals("new-slug", answer.getObject("pending").getString("slug"));
        assertEquals("2026-01-01T11:00:00Z", answer.getObject("pending").getString("appliesAt"));
        assertEquals(60, answer.getInt("windowMinutes"));
        assertEquals(30, answer.getInt("cooldownDays"));
    }

    @Test
    void aRefusalComesBackAsA400WithTheReason() {
        when(changes.request(anyLong(), anyString())).thenReturn(new SlugChangeService.Outcome("Too soon."));
        ApiError e = assertThrows(ApiError.class, () -> routes.route(G, "POST", "", () -> DataObject.empty().put("slug", "x-y-z")));
        assertEquals(400, e.status);
    }

    @Test
    void lockInCancelAndUnknownPaths() {
        when(changes.lockIn(G)).thenReturn(new SlugChangeService.Outcome(null));
        when(changes.status(G)).thenReturn(new SlugChangeRules.Status(null, null, null, null));
        assertNull(routes.route(G, "POST", "lock-in", DataObject::empty).opt("pending").orElse(null));
        routes.route(G, "POST", "cancel", DataObject::empty);
        verify(changes).cancel(G);
        assertNull(routes.route(G, "GET", "nope", DataObject::empty));
        assertThrows(ApiError.class, () -> routes.route(G, "GET", "lock-in", DataObject::empty));
    }
}
