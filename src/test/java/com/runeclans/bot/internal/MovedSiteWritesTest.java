package com.runeclans.bot.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.hubsites.HubSites;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The dashboard's site writes answer 409 moved with SITES_BACKEND=hub, before any route runs, and pass through with it off (T-506). */
class MovedSiteWritesTest {
    private record Reply(boolean refused, int status, String body) {}

    private static Reply call(HubSites sites, String method, String path) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] status = {-1};
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost" + path));
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(out);
        doAnswer(inv -> {
            status[0] = inv.getArgument(0);
            return null;
        }).when(exchange).sendResponseHeaders(any(Integer.class), any(Long.class));
        boolean refused = InternalApiServer.refusedAsMoved(sites, exchange);
        return new Reply(refused, status[0], out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void withTheSwitchOnASiteWriteIsMoved() throws Exception {
        Reply r = call(HubSites.on(null), "PUT", "/internal/admin/clan-site/layout");
        assertTrue(r.refused());
        assertEquals(409, r.status());
        assertEquals("hub", DataObject.fromJson(r.body()).getString("moved"));
    }

    @Test
    void readsAndOtherRoutesPassThrough() throws Exception {
        assertFalse(call(HubSites.on(null), "GET", "/internal/admin/clan-site/layout").refused());
        assertFalse(call(HubSites.on(null), "PUT", "/internal/admin/selfroles").refused());
    }

    @Test
    void withTheSwitchOffEveryWritePassesThrough() throws Exception {
        assertFalse(call(HubSites.off(), "PUT", "/internal/admin/clan-site/layout").refused());
        assertFalse(call(HubSites.off(), "POST", "/internal/admin/overall/owner-sites/7/address").refused());
    }
}
