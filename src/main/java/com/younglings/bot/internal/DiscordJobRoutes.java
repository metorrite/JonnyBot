package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.discord.jobs.DiscordJob;
import com.younglings.bot.discord.jobs.DiscordOutbox;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The website's view of the Discord job outbox (T-456), under {@code /internal/discord-jobs}. Never waits on Discord: it reads the job table.
 * <ul>
 *   <li>{@code GET ?actorId=&ids=1,2,3[&guildId=]} → {@code {jobs: [{id, kind, state, attempts, error, result, nextAttemptAt, updatedAt}]}}</li>
 *   <li>{@code POST /{id}/retry?actorId=[&guildId=]} → the job, queued again (only a failed one)</li>
 * </ul>
 * Like the {@code /internal/me/} routes, {@code actorId} is trusted: the website takes it from the signed-in session. A person only ever sees
 * their own jobs (only those about the server named, when one is); any other id reads as missing. A clan site knows its address, not its
 * server, so the server is optional.
 */
@BService
public class DiscordJobRoutes implements InternalRouteModule {
    static final String PREFIX = "/internal/discord-jobs";
    static final int MAX_IDS = 50;

    private final DiscordOutbox outbox;
    private final RateLimiter reads = new RateLimiter(120, 250);

    public DiscordJobRoutes(DiscordOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    record Answer(int status, DataObject body) {}

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String rest = path.length() > PREFIX.length() ? path.substring(PREFIX.length()) : "";
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        Answer answer;
        try {
            answer = route(method, rest, query(exchange, "guildId"), query(exchange, "actorId"), query(exchange, "ids"));
        } catch (RuntimeException e) {
            answer = new Answer(500, error("Internal error"));
        }
        InternalApiServer.sendJson(exchange, answer.status(), answer.body());
    }

    Answer route(String method, String rest, String rawGuild, String rawActor, String rawIds) {
        long actorId = id(rawActor);
        if (actorId == 0) return new Answer(400, error("Give actorId."));
        Long guildId = null;
        if (rawGuild != null && !rawGuild.isBlank()) {
            guildId = id(rawGuild);
            if (guildId == 0) return new Answer(400, error("guildId is a server id."));
        }
        String[] parts = rest.replaceAll("^/+|/+$", "").split("/");
        if (method.equals("GET") && (rest.isEmpty() || rest.equals("/"))) return list(guildId, actorId, rawIds);
        if (method.equals("POST") && parts.length == 2 && parts[1].equals("retry")) return retry(guildId, actorId, parts[0]);
        return new Answer(404, error("Not found"));
    }

    private Answer list(Long guildId, long actorId, String rawIds) {
        if (rawIds == null || rawIds.isBlank()) return new Answer(400, error("Give the job ids."));
        List<Long> ids = new ArrayList<>();
        for (String raw : rawIds.split(",")) {
            long id = number(raw);
            if (id <= 0) return new Answer(400, error("A job id is a number."));
            if (!ids.contains(id)) ids.add(id);
        }
        if (ids.size() > MAX_IDS) return new Answer(400, error("Ask about " + MAX_IDS + " jobs at most."));
        if (!reads.tryAcquire(Long.toString(actorId))) return new Answer(429, error("Too many requests"));
        DataArray jobs = DataArray.empty();
        for (DiscordJob job : outbox.find(ids, guildId, actorId)) jobs.add(job.toJson());
        return new Answer(200, DataObject.empty().put("jobs", jobs));
    }

    private Answer retry(Long guildId, long actorId, String rawId) {
        long id = number(rawId);
        if (id <= 0) return new Answer(400, error("A job id is a number."));
        if (!outbox.retry(id, guildId, actorId)) return new Answer(409, error("Only a change that failed can be tried again."));
        return outbox.get(id).map(job -> new Answer(200, job.toJson())).orElseGet(() -> new Answer(404, error("Not found")));
    }

    private static long id(String raw) {
        return raw != null && raw.matches("\\d{15,20}") ? number(raw) : 0;
    }

    private static long number(String raw) {
        try {
            return raw == null ? 0 : Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static DataObject error(String message) {
        return DataObject.empty().put("error", message);
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
