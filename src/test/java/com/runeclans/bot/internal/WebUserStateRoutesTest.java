package com.runeclans.bot.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runeclans.bot.web.WebUserState;
import com.runeclans.bot.web.WebUserStateRepository;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The website user-state routes (T-407): what they refuse, and the shape they answer with. The storage is mocked; the DB test covers it. */
class WebUserStateRoutesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ME = String.valueOf(Long.parseLong("9".repeat(17)) + 61);
    private WebUserStateRepository repository;
    private WebUserStateRoutes routes;

    @BeforeEach
    void setUp() {
        repository = mock(WebUserStateRepository.class);
        routes = new WebUserStateRoutes(repository);
    }

    private static JsonNode body(String userId, String scope, String key, Object value) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", userId);
        m.put("scope", scope);
        m.put("key", key);
        m.put("value", value);
        return JSON.valueToTree(m);
    }

    @Test
    void badInputIsRefusedBeforeTheDatabase() throws Exception {
        assertEquals(400, routes.get("12", "global", null).status(), "not a Discord id");
        assertEquals(400, routes.get(ME, "site:abc", null).status(), "a site scope is site:<server id>");
        assertEquals(400, routes.get(ME, "global", "ok,Not-Ok").status(), "keys are lower case");
        assertEquals(400, routes.put(body(ME, "global", "theme", null)).status(), "no null values (DELETE removes one)");
        assertEquals(400, routes.put(body(ME, "global", "x".repeat(65), "1")).status(), "keys up to 64");
        assertEquals(400, routes.put(null).status());
        assertEquals(413, routes.put(body(ME, "global", "big", "x".repeat(WebUserState.MAX_VALUE_BYTES))).status(), "2 KB at most, as JSON");
        assertEquals(400, routes.delete(ME, "global", null, false).status());
        verify(repository, never()).put(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void aPutSavesTheValueAsJson() throws Exception {
        when(repository.put(anyLong(), anyString(), anyString(), anyString())).thenReturn(WebUserStateRepository.Put.SAVED);
        when(repository.get(anyLong(), eq("global"), eq(List.of()))).thenReturn(Map.of("tour.basics", "{\"step\": 2}"));
        WebUserStateRoutes.Answer answer = routes.put(body(ME, "global", "tour.basics", Map.of("step", 2)));
        assertEquals(200, answer.status());
        verify(repository).put(Long.parseLong(ME), "global", "tour.basics", "{\"step\":2}");
        assertEquals(2, answer.body().getObject("values").getObject("tour.basics").getInt("step"), "values come back as JSON, not text");
    }

    @Test
    void theFiftyFirstKeyIsAConflict() throws Exception {
        when(repository.put(anyLong(), anyString(), anyString(), anyString())).thenReturn(WebUserStateRepository.Put.TOO_MANY_KEYS);
        assertEquals(409, routes.put(body(ME, "global", "one-more", true)).status());
    }

    @Test
    void deleteAllIsTheDeleteMyDataRoute() {
        when(repository.deleteAll(Long.parseLong(ME))).thenReturn(3);
        WebUserStateRoutes.Answer answer = routes.delete(ME, null, null, true);
        assertEquals(200, answer.status());
        assertEquals(3, answer.body().getInt("deleted"));
    }

    @Test
    void valuesKeepTheirJsonTypes() {
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("a", "\"dark\"");
        stored.put("b", "7");
        stored.put("c", "[1, 2]");
        stored.put("d", "true");
        DataObject json = WebUserStateRoutes.values("global", stored);
        assertEquals("global", json.getString("scope"));
        DataObject values = json.getObject("values");
        assertEquals("dark", values.getString("a"));
        assertEquals(7, values.getInt("b"));
        assertEquals(2, values.getArray("c").length());
        assertTrue(values.getBoolean("d"));
    }

    /** The JDK server picks the longest matching context, so this module gets its path without any change to MemberApi's /internal/me/. */
    @Test
    void itsOwnContextWinsOverMeAndLeavesTheRestAlone() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/me/", exchange -> {
            exchange.sendResponseHeaders(418, -1);
            exchange.close();
        });
        server.createContext(routes.prefix(), routes::handle);
        server.start();
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var mine = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/internal/me/ui-state?userId=" + ME + "&scope=global")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, mine.statusCode(), mine.body());
            assertTrue(mine.body().contains("\"values\""));
            var other = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/internal/me/settings")).build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            assertEquals(418, other.statusCode(), "the member routes still answer their own paths");
            var lookalike = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/internal/me/ui-statex")).build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            assertEquals(404, lookalike.statusCode(), "a longer look-alike path isn't this route");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theRules() {
        assertTrue(WebUserState.scope("global"));
        assertTrue(WebUserState.scope("site:" + "1".repeat(18)));
        assertFalse(WebUserState.scope("site:"));
        assertFalse(WebUserState.scope("Global"));
        assertTrue(WebUserState.key("tour.edit-mode-basics"));
        assertFalse(WebUserState.key(""));
        assertFalse(WebUserState.key("a b"));
        assertEquals(0, WebUserState.userId("-1"));
        assertTrue(WebUserState.fits("x".repeat(WebUserState.MAX_VALUE_BYTES)));
        assertFalse(WebUserState.fits("é".repeat(WebUserState.MAX_VALUE_BYTES / 2 + 1)), "bytes, not characters");
    }
}
