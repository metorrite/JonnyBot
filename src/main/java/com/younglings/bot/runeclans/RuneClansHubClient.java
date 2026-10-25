package com.younglings.bot.runeclans;

import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * JonnyBot as a client of the RuneClans hub's public API (runeclans-hub docs/API.md), with JonnyBot's own first-party key. Every call is a
 * small JSON request with {@code Authorization: Bearer <key>}; nothing here is used unless {@link #enabled()} (see {@link RuneClansSettings}).
 * The key and any Discord id are never logged.
 * <p>
 * For the bot reading hub data instead of its own database (runeclans-hub PLATFORM-DESIGN B-0.7, 9.2, 9.4): one keep-alive HTTP/1.1
 * connection pool, {@link HubRetry} retries for calls safe to repeat, {@link #read} with a small bounded cache that hub changes empty
 * ({@link #onHubChange}) and that answers "may be a few minutes old" while the hub is down, and {@link #write} with a bounded in-memory
 * retry queue for writes the hub can't take right now.
 */
@BConfiguration
public class RuneClansHubClient {
    private static final Logger log = LoggerFactory.getLogger(RuneClansHubClient.class);

    /** One HTTP exchange with the hub; null when it can't be reached. Replaceable in tests. */
    public interface Transport {
        Answer call(String method, String path, String body);
    }

    /** The hub's status and JSON answer ({@code json} null when the body wasn't JSON), and its {@code Retry-After} in seconds, if any. */
    public record Answer(int status, DataObject json, Integer retryAfterSeconds) {
        public Answer(int status, DataObject json) {
            this(status, json, null);
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** A {@link #read} answer: from the cache or not, and stale when the hub couldn't be asked and the cached copy is past its fresh time. */
    public record Read(Answer answer, boolean fromCache, boolean stale) {}

    private final RuneClansSettings settings;
    private final Transport transport;
    private HubCache cache;
    private HubWriteQueue writes;

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use the other. */
    @BService
    public static RuneClansHubClient runeClansHubClient(BotConfig config) {
        RuneClansSettings settings = RuneClansSettings.from(config::getRuneClansSetting);
        if (settings.problem() != null) log.error("RuneClans hub client is off: {}", settings.problem());
        else if (settings.enabled()) log.info("RuneClans hub client is on ({}).", settings.baseUrl());
        return new RuneClansHubClient(settings, settings.enabled() ? HubRetry.around(http(settings)) : (m, p, b) -> null);
    }

    RuneClansHubClient(RuneClansSettings settings, Transport transport) {
        this.settings = settings;
        this.transport = transport;
        withClock(Clock.systemUTC());
    }

    /** Fresh cache and write queue on {@code clock}; for tests. */
    RuneClansHubClient withClock(Clock clock) {
        this.cache = new HubCache(HubCache.MAX_ENTRIES, clock);
        this.writes = new HubWriteQueue(transport, clock, HubWriteQueue.MAX_SIZE);
        return this;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /** A raw call, for {@code RsLinkAuth}'s transport. Null when off or unreachable. */
    public Answer call(String method, String path, String body) {
        if (!enabled()) return null;
        return transport.call(method, path, body);
    }

    // ---------- cached reads, queued writes, change eviction ----------

    /**
     * {@code GET path} through the cache: a fresh cached answer if there is one, else the hub's (kept when it is a success, tagged with
     * {@code tags} such as {@code clan:<id>} so {@link #onHubChange} can drop it). When the hub can't be reached or is busy, the cached copy
     * is used while it is under 15 minutes old, marked stale. Null when off, or when the hub is down and nothing is cached.
     */
    public Read read(String path, String... tags) {
        return read(path, HubCache.FRESH, tags);
    }

    /** {@link #read}, keeping a success fresh for {@code keepFresh} (at most 15 minutes): for answers that a tagged hub change empties. */
    public Read read(String path, Duration keepFresh, String... tags) {
        if (!enabled()) return null;
        Answer fresh = cache.fresh(path);
        if (fresh != null) return new Read(fresh, true, false);
        Answer a = transport.call("GET", path, null);
        if (a != null && a.ok()) {
            cache.put(path, a, Set.of(tags), keepFresh);
            return new Read(a, false, false);
        }
        if (HubRetry.worthRetrying(a)) {
            HubCache.Hit hit = cache.lookup(path);
            if (hit != null) return new Read(hit.answer(), true, hit.stale());
        }
        return a == null ? null : new Read(a, false, false);
    }

    /**
     * A write ({@code POST}/{@code PUT}/{@code DELETE}) that may wait: sent now, or held up to 5 minutes when the hub can't take it
     * ({@link HubWriteQueue.Outcome#QUEUED}: tell the member "saved, will sync shortly"); {@code done} hears how a held write ended.
     * {@link HubWriteQueue.Outcome#REFUSED} when off, when the queue is full, or when the hub said no.
     */
    public HubWriteQueue.Result write(String method, String path, String body, Consumer<HubWriteQueue.Result> done) {
        if (!enabled()) return new HubWriteQueue.Result(HubWriteQueue.Outcome.REFUSED, null);
        return writes.submit(new HubWriteQueue.Write(method, path, body, done));
    }

    /** Sends the held writes (the feed lane runs this every 10 seconds). Returns how many were sent. */
    public int drainWrites() {
        return enabled() ? writes.drain() : 0;
    }

    /** A hub change (feed entry or verified webhook: {@code kind} and {@code data}): drops the cached answers it makes out of date. */
    public int onHubChange(String kind, DataObject data) {
        return HubChanges.evict(cache, kind, data);
    }

    HubCache cache() {
        return cache;
    }

    HubWriteQueue writes() {
        return writes;
    }

    // ---------- reads ----------

    /** {@code GET /v1/feed}: JonnyBot's change feed after {@code cursor} (null for the start). */
    public Answer feed(String cursor, int limit) {
        return call("GET", "/v1/feed?limit=" + limit + (cursor == null ? "" : "&cursor=" + enc(cursor)), null);
    }

    /** The Discord id behind one of JonnyBot's aliases, when the player granted JonnyBot identity. */
    public Optional<Long> discordIdOf(String alias) {
        Answer a = call("GET", "/v1/players/" + enc(alias), null);
        if (a == null || !a.ok() || a.json() == null) return Optional.empty();
        String id = a.json().getString("discordId", null);
        try {
            return id == null ? Optional.empty() : Optional.of(Long.parseLong(id));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * {@code GET /v1/guilds/{id}/events}: the newest plugin events of a server's members, as the hub filters them (only members whose grant
     * to JonnyBot covers that server, only categories both the grant and the player's current consent allow). For clan websites (T-303).
     */
    public Answer guildEvents(long guildId, int limit) {
        return call("GET", "/v1/guilds/" + guildId + "/events?limit=" + Math.max(1, Math.min(limit, 100)), null);
    }

    // ---------- writes ----------

    /** {@code PUT /v1/guilds/{id}}: tells the hub a server JonnyBot is in, by name. */
    public boolean putGuild(long guildId, String name) {
        Answer a = call("PUT", "/v1/guilds/" + guildId, DataObject.empty().put("name", name == null ? null : name.length() > 100 ? name.substring(0, 100) : name).toString());
        return a != null && a.ok();
    }

    /** {@code PUT /v1/guilds/{id}/members/{alias}}: a player who granted JonnyBot is in that server. 404 when their grant doesn't cover it. */
    public boolean putGuildMember(long guildId, String alias) {
        Answer a = call("PUT", "/v1/guilds/" + guildId + "/members/" + enc(alias), "{}");
        return a != null && a.ok();
    }

    /** {@code Retry-After} in whole seconds (the hub sends seconds), or null when absent or not a small number. */
    static Integer retryAfter(String raw) {
        if (raw == null || !raw.strip().matches("\\d{1,5}")) return null;
        return Integer.parseInt(raw.strip());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Transport http(RuneClansSettings settings) {
        // one client for the bot's life: HTTP/1.1 keeps its connections to the hub open between calls (no connect cost per call)
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
        return (method, path, body) -> {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(settings.baseUrl() + path)).timeout(Duration.ofSeconds(8))
                        .header("Authorization", "Bearer " + settings.apiKey()).header("Content-Type", "application/json").header("User-Agent", "JonnyBot runeclans-client");
                request = body == null ? request.method(method, HttpRequest.BodyPublishers.noBody()) : request.method(method, HttpRequest.BodyPublishers.ofString(body));
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                DataObject json = null;
                try {
                    json = DataObject.fromJson(response.body());
                } catch (RuntimeException ignored) {
                    // not JSON: no body
                }
                return new Answer(response.statusCode(), json, retryAfter(response.headers().firstValue("Retry-After").orElse(null)));
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return null;
            }
        };
    }
}
