package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.plugin.PluginCategory;
import com.younglings.bot.plugin.PluginPayload;
import com.younglings.bot.plugin.PluginRepository.Token;
import com.younglings.bot.plugin.PluginService;
import com.younglings.bot.plugin.PluginService.PluginException;
import com.younglings.bot.plugin.PluginTokens;
import com.younglings.bot.plugin.RsLinkAuth;
import com.younglings.bot.plugin.RuneSyncPanelService;
import com.younglings.bot.plugin.RuneSyncService;
import com.younglings.bot.plugin.RuneSyncService.RuneSyncException;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The plugin API, under {@code /internal/plugin/}. {@link InternalApiServer} has already checked the shared secret (the public website
 * is what faces the internet and forwards to this), then there are two halves:
 * <ul>
 *   <li><b>The plugin</b>, identified by its token in {@code X-Plugin-Token} or, for an upload from RuneSync, by an RS Link signed assertion in
 *       {@code Authorization: RSLink ...} (see {@link RsLinkAuth}): {@code POST ingest} (a batch of events, schema v1) and
 *       {@code GET whoami} (which names it may report for and what the player has allowed); and RuneSync's own routes (T-202):
 *       {@code GET runesync/config}, {@code PUT runesync/consent}, {@code POST runesync/diagnostics} and {@code GET runesync/panel}.</li>
 *   <li><b>The player</b>, identified by a {@code userId} the website takes from its verified sign-in (the same trust as {@code /internal/me/}):
 *       {@code GET me}, {@code GET me/runesync}, {@code POST me/tokens}, {@code DELETE me/tokens/{id}}, {@code PUT me/consent} and {@code DELETE me/data}.</li>
 * </ul>
 * Nothing here is about one server: a player's plugin data belongs to the player.
 */
@BService
public class PluginApi {
    private static final Logger log = LoggerFactory.getLogger(PluginApi.class);
    private static final String PREFIX = "/internal/plugin/";
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final PluginService service;
    private final RsLinkAuth rsLink;
    private final RuneSyncService runeSync;
    private final RuneSyncPanelService panel;
    /** 60 requests a minute per token, with a short burst. */
    private final RateLimiter ingestLimit = new RateLimiter(60, 1_000);
    private final RateLimiter accountLimit = new RateLimiter(20, 3_000);
    /** RuneSync's settings and diagnostics calls: a client asks every ~10 minutes, so this is generous. */
    private final RateLimiter runeSyncLimit = new RateLimiter(10, 6_000);

    /** Who a plugin call is from: the Discord account, the RSN RS Link vouches for (null on the token path) and the key for rate limits. */
    private record Caller(long userId, String linkedRsn, String limitKey) {}

    public PluginApi(PluginService service, RsLinkAuth rsLink, RuneSyncService runeSync, RuneSyncPanelService panel) {
        this.service = service;
        this.rsLink = rsLink;
        this.runeSync = runeSync;
        this.panel = panel;
    }

    /** Called after the shared secret has been checked. */
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (!path.startsWith(PREFIX)) {
            send(exchange, 404, error("Not found"));
            return;
        }
        String[] parts = path.substring(PREFIX.length()).split("/");
        try {
            if (parts[0].equals("ingest") && parts.length == 1) {
                if (!method.equals("POST")) {
                    send(exchange, 405, error("Method not allowed"));
                    return;
                }
                ingest(exchange);
            } else if (parts[0].equals("whoami") && parts.length == 1) {
                if (!method.equals("GET")) {
                    send(exchange, 405, error("Method not allowed"));
                    return;
                }
                whoami(exchange);
            } else if (parts[0].equals("runesync") && parts.length == 2) {
                runeSync(exchange, method, parts[1]);
            } else if (parts[0].equals("me")) {
                account(exchange, method, parts);
            } else {
                send(exchange, 404, error("Not found"));
            }
        } catch (PluginException e) {
            send(exchange, 400, error(e.getMessage()));
        } catch (RuneSyncException e) {
            send(exchange, e.status(), error(e.getMessage()));
        }
    }

    // ---------- the plugin ----------

    private Optional<Token> authenticate(HttpExchange exchange) throws IOException {
        Optional<Token> token = service.authenticate(exchange.getRequestHeaders().getFirst("X-Plugin-Token"));
        if (token.isEmpty()) send(exchange, 401, error("That token isn't valid. Make a new one on your JonnyBot profile."));
        return token;
    }

    /**
     * Who is calling: either RuneSync signed through RS Link ({@code Authorization: RSLink <assertion>}), or a manual token
     * ({@code X-Plugin-Token}). Empty when the answer has already been sent (401, or 503 while linking is unavailable).
     */
    private Optional<Caller> caller(HttpExchange exchange) throws IOException {
        String assertion = RsLinkAuth.assertionFrom(exchange.getRequestHeaders().getFirst("Authorization"));
        if (assertion != null) {
            Optional<RsLinkAuth.Identity> identity;
            try {
                identity = rsLink.authenticate(assertion);
            } catch (RsLinkAuth.Unavailable e) {
                exchange.getResponseHeaders().set("Retry-After", "30");
                send(exchange, 503, error("Linking is unavailable for a moment. Keep the batch and try again shortly."));
                return Optional.empty();
            }
            if (identity.isEmpty()) {
                send(exchange, 401, error("That RS Link proof isn't valid any more. Check the link in RS Link."));
                return Optional.empty();
            }
            return Optional.of(new Caller(identity.get().discordUserId(), identity.get().rsn(), "rsl:" + identity.get().discordUserId()));
        }
        Optional<Token> token = authenticate(exchange);
        return token.map(t -> new Caller(t.discordUserId(), null, Long.toString(t.id())));
    }

    private void ingest(HttpExchange exchange) throws IOException {
        Optional<Caller> caller = caller(exchange);
        if (caller.isEmpty()) return;
        long userId = caller.get().userId();
        String linkedRsn = caller.get().linkedRsn();
        String limitKey = caller.get().limitKey();
        if (!ingestLimit.tryAcquire(limitKey)) {
            exchange.getResponseHeaders().set("Retry-After", "2");
            send(exchange, 429, error("Too many requests. Send fewer, larger batches (up to " + PluginPayload.MAX_EVENTS + " events)."));
            return;
        }
        DataObject body;
        try {
            byte[] bytes = readLimited(exchange.getRequestBody());
            if (bytes == null) {
                send(exchange, 413, error("The batch is larger than " + MAX_BODY_BYTES / 1024 + " KB."));
                return;
            }
            body = DataObject.fromJson(bytes);
        } catch (RuntimeException e) {
            send(exchange, 400, error("That batch isn't valid JSON."));
            return;
        }
        PluginPayload.Parsed batch = PluginPayload.parse(body, Instant.now());
        if (batch.problem() != null) {
            send(exchange, 400, error(batch.problem()));
            return;
        }
        var result = linkedRsn != null ? service.ingestVerified(userId, linkedRsn, batch) : service.ingest(userId, batch);
        if (result.forbidden() != null) {
            send(exchange, 403, error(result.forbidden()));
            return;
        }
        try {
            runeSync.recordBatch(userId, batch.client(), result.accepted(), result.duplicates(), result.rejected().size(), Instant.now());
        } catch (RuntimeException e) {
            // the device card is a convenience: the batch is stored either way
            log.warn("Couldn't record the plugin client status for user {}", userId, e);
        }
        DataArray rejected = DataArray.empty();
        for (var r : result.rejected()) rejected.add(DataObject.empty().put("id", r.id()).put("reason", r.reason()));
        send(exchange, 200, DataObject.empty().put("accepted", result.accepted()).put("duplicates", result.duplicates()).put("rejected", rejected)
                .put("consented", categoryKeys(result.consented())));
    }

    private void whoami(HttpExchange exchange) throws IOException {
        Optional<Token> token = authenticate(exchange);
        if (token.isEmpty()) return;
        long userId = token.get().discordUserId();
        DataArray names = DataArray.empty();
        for (String rsn : service.reportableNames(userId)) names.add(rsn);
        send(exchange, 200, DataObject.empty().put("schema", PluginPayload.SCHEMA).put("maxEventsPerBatch", PluginPayload.MAX_EVENTS).put("rsns", names)
                .put("consented", categoryKeys(service.consent(userId))).put("categories", categoryOptions()));
    }

    /**
     * RuneSync's settings and bug-report calls (RUNESYNC-DESIGN.md sections 6, 7 and 10), from the plugin itself:
     * <ul>
     *   <li>{@code GET runesync/config?client=RuneSync%200.1.0&paused=false}: consent, panelAvailable, minClientVersion, sendEverySeconds. The
     *       optional {@code client} and {@code paused} only feed the profile's device card.</li>
     *   <li>{@code POST} (or {@code PUT}) {@code runesync/consent} {@code {"categories": ["XP", ...]}}: the in-game toggles, written to the same
     *       record as the profile.</li>
     *   <li>{@code POST runesync/diagnostics} (a snapshot): stored redacted for 14 days, answers {@code {"id": "RS-7K2M9Q"}}.</li>
     *   <li>{@code GET runesync/panel}: the in-game clan panel's data (T-206): for each of the member's clans that switched the panel on, its
     *       open and running events, the member's own points and own progress. Cached 60 seconds per member.</li>
     * </ul>
     */
    private void runeSync(HttpExchange exchange, String method, String route) throws IOException {
        // The game client can only send GET and POST (local API library docs/core-http.md), so consent takes POST as well as PUT.
        Set<String> methods = switch (route) {
            case "config" -> Set.of("GET");
            case "consent" -> Set.of("PUT", "POST");
            case "diagnostics" -> Set.of("POST");
            case "panel" -> Set.of("GET");
            default -> null;
        };
        if (methods == null) {
            send(exchange, 404, error("Not found"));
            return;
        }
        if (!methods.contains(method)) {
            send(exchange, 405, error("Method not allowed"));
            return;
        }
        Optional<Caller> caller = caller(exchange);
        if (caller.isEmpty()) return;
        long userId = caller.get().userId();
        if (!runeSyncLimit.tryAcquire(caller.get().limitKey())) {
            exchange.getResponseHeaders().set("Retry-After", "10");
            send(exchange, 429, error("Too many requests. RuneSync asks for its settings every 10 minutes."));
            return;
        }
        if (route.equals("panel")) {
            send(exchange, 200, panel.panel(userId, Instant.now()));
            return;
        }
        if (route.equals("config")) {
            String client = query(exchange, "client");
            runeSync.checkIn(userId, client == null ? null : URLDecoder.decode(client, StandardCharsets.UTF_8), "true".equals(query(exchange, "paused")), Instant.now());
            send(exchange, 200, runeSync.config(userId));
            return;
        }
        DataObject body;
        try {
            byte[] bytes = readLimited(exchange.getRequestBody());
            if (bytes == null) {
                send(exchange, 413, error("That request is too large."));
                return;
            }
            body = DataObject.fromJson(bytes);
        } catch (RuntimeException e) {
            send(exchange, 400, error("That request isn't valid JSON."));
            return;
        }
        if (route.equals("consent")) send(exchange, 200, runeSync.setConsent(userId, body));
        else send(exchange, 200, runeSync.storeDiagnostics(userId, body, Instant.now()));
    }

    // ---------- the player ----------

    private void account(HttpExchange exchange, String method, String[] parts) throws IOException {
        long userId;
        DataObject body = null;
        try {
            if (method.equals("GET") || method.equals("DELETE")) {
                userId = parseId(query(exchange, "userId"));
            } else {
                byte[] bytes = readLimited(exchange.getRequestBody());
                if (bytes == null) {
                    send(exchange, 413, error("That request is too large."));
                    return;
                }
                body = DataObject.fromJson(bytes);
                userId = parseId(body.getString("userId", null));
            }
        } catch (RuntimeException e) {
            send(exchange, 400, error("That request couldn't be read."));
            return;
        }
        if (!accountLimit.tryAcquire(Long.toString(userId))) {
            send(exchange, 429, error("Slow down a little."));
            return;
        }

        if (parts.length == 1 && method.equals("GET")) {
            send(exchange, 200, userView(userId));
        } else if (parts.length == 2 && parts[1].equals("runesync") && method.equals("GET")) {
            send(exchange, 200, runeSync.memberView(userId, Instant.now()));
        } else if (parts.length == 2 && parts[1].equals("tokens") && method.equals("POST")) {
            var created = service.createToken(userId, body == null ? null : body.getString("label", null));
            send(exchange, 200, DataObject.empty().put("id", Long.toString(created.id())).put("token", created.token()).put("preview", created.preview())
                    .put("label", created.label()).put("view", userView(userId)));
        } else if (parts.length == 3 && parts[1].equals("tokens") && method.equals("DELETE")) {
            long tokenId;
            try {
                tokenId = parseId(parts[2]);
            } catch (RuntimeException e) {
                send(exchange, 400, error("That isn't a valid token."));
                return;
            }
            if (!service.revokeToken(userId, tokenId)) {
                send(exchange, 404, error("You have no active token like that."));
                return;
            }
            send(exchange, 200, userView(userId));
        } else if (parts.length == 2 && parts[1].equals("consent") && method.equals("PUT")) {
            Set<PluginCategory> chosen = EnumSet.noneOf(PluginCategory.class);
            if (body != null && body.hasKey("categories") && !body.isNull("categories")) {
                DataArray given = body.getArray("categories");
                for (int i = 0; i < given.length(); i++) {
                    try {
                        chosen.add(PluginCategory.valueOf(given.getString(i).strip().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        send(exchange, 400, error("One of those categories isn't one JonnyBot knows."));
                        return;
                    }
                }
            }
            service.setConsent(userId, chosen);
            send(exchange, 200, userView(userId));
        } else if (parts.length == 2 && parts[1].equals("data") && method.equals("DELETE")) {
            int removed = service.deleteAllData(userId);
            runeSync.deleteFor(userId);
            send(exchange, 200, DataObject.empty().put("removedEvents", removed).put("view", userView(userId)));
        } else {
            send(exchange, 404, error("Not found"));
        }
    }

    private DataObject userView(long userId) {
        DataArray tokens = DataArray.empty();
        for (Token t : service.tokens(userId)) {
            tokens.add(DataObject.empty().put("id", Long.toString(t.id())).put("label", t.label()).put("preview", t.preview()).put("createdAt", t.createdAt().toString())
                    .put("lastUsedAt", t.lastUsedAt() == null ? null : t.lastUsedAt().toString()).put("revoked", !t.active()));
        }
        DataArray names = DataArray.empty();
        for (String rsn : service.reportableNames(userId)) names.add(rsn);
        return DataObject.empty().put("tokens", tokens).put("rsns", names).put("consented", categoryKeys(service.consent(userId))).put("categories", categoryOptions())
                .put("storedEvents", service.storedEvents(userId)).put("maxTokens", PluginTokens.MAX_ACTIVE_PER_USER);
    }

    // ---------- helpers ----------

    private static DataArray categoryKeys(Set<PluginCategory> categories) {
        DataArray array = DataArray.empty();
        for (PluginCategory c : PluginCategory.values()) if (categories.contains(c)) array.add(c.name());
        return array;
    }

    private static DataArray categoryOptions() {
        DataArray array = DataArray.empty();
        for (PluginCategory c : PluginCategory.values()) array.add(DataObject.empty().put("key", c.name()).put("label", c.label()).put("description", c.description()));
        return array;
    }

    private static long parseId(String raw) {
        if (raw == null || !raw.strip().matches("\\d{1,20}")) throw new IllegalArgumentException("not an id");
        return Long.parseLong(raw.strip());
    }

    private static String query(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return null;
    }

    /** The whole body, or null if it is bigger than the limit. */
    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_BODY_BYTES) return null;
        }
        return out.toByteArray();
    }

    private static DataObject error(String message) {
        return DataObject.empty().put("error", message);
    }

    private static void send(HttpExchange exchange, int status, DataObject body) throws IOException {
        InternalApiServer.sendJson(exchange, status, body);
    }
}
