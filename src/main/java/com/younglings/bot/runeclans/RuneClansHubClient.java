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
import java.time.Duration;
import java.util.Optional;

/**
 * JonnyBot as a client of the RuneClans hub's public API (runeclans-hub docs/API.md), with JonnyBot's own first-party key. Every call is a
 * small JSON request with {@code Authorization: Bearer <key>}; nothing here is used unless {@link #enabled()} (see {@link RuneClansSettings}).
 * The key and any Discord id are never logged.
 */
@BConfiguration
public class RuneClansHubClient {
    private static final Logger log = LoggerFactory.getLogger(RuneClansHubClient.class);

    /** One HTTP exchange with the hub; null when it can't be reached. Replaceable in tests. */
    public interface Transport {
        Answer call(String method, String path, String body);
    }

    /** The hub's status and JSON answer ({@code json} null when the body wasn't JSON). */
    public record Answer(int status, DataObject json) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final RuneClansSettings settings;
    private final Transport transport;

    /** The bot's instance. A factory, because the framework needs a service class to have exactly one constructor and tests use the other. */
    @BService
    public static RuneClansHubClient runeClansHubClient(BotConfig config) {
        RuneClansSettings settings = RuneClansSettings.from(config::getRuneClansSetting);
        if (settings.problem() != null) log.error("RuneClans hub client is off: {}", settings.problem());
        else if (settings.enabled()) log.info("RuneClans hub client is on ({}).", settings.baseUrl());
        return new RuneClansHubClient(settings, settings.enabled() ? http(settings) : (m, p, b) -> null);
    }

    RuneClansHubClient(RuneClansSettings settings, Transport transport) {
        this.settings = settings;
        this.transport = transport;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /** A raw call, for {@code RsLinkAuth}'s transport. Null when off or unreachable. */
    public Answer call(String method, String path, String body) {
        if (!enabled()) return null;
        return transport.call(method, path, body);
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

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Transport http(RuneClansSettings settings) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
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
                return new Answer(response.statusCode(), json);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return null;
            }
        };
    }
}
