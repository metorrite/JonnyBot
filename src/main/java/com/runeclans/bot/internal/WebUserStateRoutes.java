package com.runeclans.bot.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.web.WebUserState;
import com.runeclans.bot.web.WebUserStateRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A signed-in person's small website settings (T-407, runeclans docs/BUILDER-V3.md 15.9 and 9.3), under {@code /internal/me/ui-state}:
 * <ul>
 *   <li>{@code GET ?userId=&scope=&keys=a,b} → {@code {scope, values: {key: value}}} (all keys in the scope when {@code keys} is left out)</li>
 *   <li>{@code PUT {userId, scope, key, value}} → the scope's values after the save</li>
 *   <li>{@code DELETE ?userId=&scope=&key=} one value; {@code DELETE ?userId=&all=1} everything kept for that person (their "delete my
 *   data")</li>
 * </ul>
 * Like the other {@code /internal/me/} routes, {@code userId} is trusted: the website must take it only from the signed-in session. A
 * person only ever reaches their own rows. Its own context (longer than {@code /internal/me/}), so {@link MemberApi} is not touched.
 */
@BService
public class WebUserStateRoutes implements InternalRouteModule {
    static final String PREFIX = "/internal/me/ui-state";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebUserStateRepository repository;
    /** As {@link MemberApi}'s own limits, per person. */
    private final RateLimiter reads = new RateLimiter(40, 500);
    private final RateLimiter writes = new RateLimiter(8, 1_500);

    public WebUserStateRoutes(WebUserStateRepository repository) {
        this.repository = repository;
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals(PREFIX) && !path.equals(PREFIX + "/")) {
            InternalApiServer.sendJson(exchange, 404, error("Not found"));
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
        Answer answer = switch (method) {
            case "GET" -> get(query(exchange, "userId"), query(exchange, "scope"), query(exchange, "keys"));
            case "PUT" -> put(readBody(exchange));
            case "DELETE" -> delete(query(exchange, "userId"), query(exchange, "scope"), query(exchange, "key"), "1".equals(query(exchange, "all")));
            default -> new Answer(405, error("Method not allowed"));
        };
        InternalApiServer.sendJson(exchange, answer.status(), answer.body());
    }

    record Answer(int status, DataObject body) {}

    Answer get(String rawUser, String scope, String rawKeys) {
        long userId = WebUserState.userId(rawUser);
        if (userId == 0) return new Answer(400, error("Bad userId"));
        if (!WebUserState.scope(scope)) return new Answer(400, error("Bad scope"));
        List<String> keys = new ArrayList<>();
        if (rawKeys != null && !rawKeys.isEmpty()) {
            for (String key : rawKeys.split(",")) {
                if (!WebUserState.key(key)) return new Answer(400, error("Bad key"));
                keys.add(key);
            }
            if (keys.size() > WebUserState.MAX_KEYS_PER_READ) return new Answer(400, error("Too many keys"));
        }
        if (!reads.tryAcquire(Long.toString(userId))) return new Answer(429, error("Too many requests"));
        return new Answer(200, values(scope, repository.get(userId, scope, keys)));
    }

    Answer put(JsonNode body) {
        if (body == null || !body.isObject()) return new Answer(400, error("Send a JSON object"));
        long userId = WebUserState.userId(body.path("userId").asText(null));
        String scope = body.path("scope").asText(null);
        String key = body.path("key").asText(null);
        JsonNode value = body.get("value");
        if (userId == 0) return new Answer(400, error("Bad userId"));
        if (!WebUserState.scope(scope)) return new Answer(400, error("Bad scope"));
        if (!WebUserState.key(key)) return new Answer(400, error("Bad key"));
        if (value == null || value.isNull() || value.isMissingNode()) return new Answer(400, error("Send a value (use DELETE to remove one)"));
        String json = value.toString();
        if (!WebUserState.fits(json)) return new Answer(413, error("That value is too big (" + WebUserState.MAX_VALUE_BYTES + " bytes at most)"));
        if (!writes.tryAcquire(Long.toString(userId))) return new Answer(429, error("Too many requests"));
        if (repository.put(userId, scope, key, json) == WebUserStateRepository.Put.TOO_MANY_KEYS) {
            return new Answer(409, error("Too many saved settings here (" + WebUserState.MAX_KEYS_PER_SCOPE + " at most)"));
        }
        return new Answer(200, values(scope, repository.get(userId, scope, List.of())));
    }

    Answer delete(String rawUser, String scope, String key, boolean all) {
        long userId = WebUserState.userId(rawUser);
        if (userId == 0) return new Answer(400, error("Bad userId"));
        if (!writes.tryAcquire(Long.toString(userId))) return new Answer(429, error("Too many requests"));
        if (all) return new Answer(200, DataObject.empty().put("deleted", repository.deleteAll(userId)));
        if (!WebUserState.scope(scope)) return new Answer(400, error("Bad scope"));
        if (!WebUserState.key(key)) return new Answer(400, error("Bad key"));
        return new Answer(200, DataObject.empty().put("deleted", repository.delete(userId, scope, key) ? 1 : 0));
    }

    /** {@code {scope, values}} with each stored JSON value put back as JSON, not as text. */
    static DataObject values(String scope, Map<String, String> stored) {
        ObjectNode values = JSON.createObjectNode();
        for (Map.Entry<String, String> e : stored.entrySet()) {
            try {
                values.set(e.getKey(), JSON.readTree(e.getValue()));
            } catch (IOException ignored) {
                // jsonb always holds valid JSON; a row that somehow doesn't is left out
            }
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("scope", scope);
        out.set("values", values);
        return DataObject.fromJson(out.toString());
    }

    private static DataObject error(String message) {
        return DataObject.empty().put("error", message);
    }

    private static JsonNode readBody(HttpExchange exchange) {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(WebUserState.MAX_VALUE_BYTES * 4 + 1);
            if (bytes.length > WebUserState.MAX_VALUE_BYTES * 4) return null;
            return bytes.length == 0 ? null : JSON.readTree(bytes);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String query(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return null;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }
}
