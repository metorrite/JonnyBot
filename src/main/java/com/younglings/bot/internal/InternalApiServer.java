package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * A small, private HTTP API exposing live guild data (online members, scheduled events) for the
 * companion website (younglings-website) to read server-side. This is deliberately NOT a public
 * API — every request must carry the correct {@code X-Internal-Secret} header, and it's meant to
 * be called only from the website's own backend, never from a browser directly.
 * <p>
 * Entirely opt-in: if {@link BotConfig#getInternalApiSecret()} or {@link BotConfig#getGuildId()}
 * aren't configured, the server simply never starts — the bot's core functionality never depends
 * on this.
 * <p>
 * Why this lives in the bot at all (rather than the website talking to Discord directly): Discord
 * only pushes member presence (online/idle/dnd/offline) over a persistent Gateway connection, the
 * kind this bot already maintains. A website's backend can't cheaply get that on its own without
 * running a second, redundant Gateway connection — so it asks the bot, which already knows.
 */
@BService
public class InternalApiServer {
    private static final Logger log = LoggerFactory.getLogger(InternalApiServer.class);
    private static final String SECRET_HEADER = "X-Internal-Secret";

    private final BotConfig botConfig;
    private final JDA jda;

    public InternalApiServer(BotConfig botConfig, JDA jda) {
        this.botConfig = botConfig;
        this.jda = jda;
        start();
    }

    private void start() {
        String secret = botConfig.getInternalApiSecret();
        Long guildId = botConfig.getGuildId();

        if (secret == null) {
            log.info("INTERNAL_API_SECRET not set — internal API disabled.");
            return;
        }
        if (guildId == null) {
            log.warn("INTERNAL_API_SECRET is set but GUILD_ID is not — internal API disabled.");
            return;
        }

        try {
            int port = botConfig.getInternalApiPort();
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

            server.createContext("/internal/online-members",
                    exchange -> handle(exchange, secret, guildId, this::buildOnlineMembers));
            server.createContext("/internal/events",
                    exchange -> handle(exchange, secret, guildId, this::buildScheduledEvents));

            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
            log.info("Internal API listening on port {}", port);
        } catch (IOException e) {
            log.error("Failed to start internal API server", e);
        }
    }

    private interface ResponseBuilder {
        DataObject build(Guild guild);
    }

    private void handle(HttpExchange exchange, String expectedSecret, long guildId, ResponseBuilder responseBuilder)
            throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, DataObject.empty().put("error", "Method not allowed"));
                return;
            }

            String provided = exchange.getRequestHeaders().getFirst(SECRET_HEADER);
            if (provided == null || !constantTimeEquals(provided, expectedSecret)) {
                sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
                return;
            }

            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                sendJson(exchange, 503, DataObject.empty().put("error", "Guild not available yet"));
                return;
            }

            sendJson(exchange, 200, responseBuilder.build(guild));
        } catch (Exception e) {
            log.error("Internal API request to {} failed", exchange.getRequestURI(), e);
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    // --- Response builders ---

    private DataObject buildOnlineMembers(Guild guild) {
        DataArray members = DataArray.empty();

        for (Member member : guild.getMembers()) {
            if (member.getUser().isBot()) continue;

            OnlineStatus status = member.getOnlineStatus();
            if (status == OnlineStatus.OFFLINE || status == OnlineStatus.UNKNOWN) continue;

            List<Role> roles = member.getRoles();
            Role topRole = roles.isEmpty() ? null : roles.getFirst();

            DataObject entry = DataObject.empty()
                    .put("id", member.getId())
                    .put("displayName", member.getEffectiveName())
                    .put("avatarUrl", member.getEffectiveAvatarUrl())
                    .put("status", status.getKey());

            entry.put("topRole", topRole == null ? null : DataObject.empty()
                    .put("name", topRole.getName())
                    .put("colorRaw", topRole.getColors().getPrimaryRaw()));

            members.add(entry);
        }

        return DataObject.empty().put("members", members);
    }

    private DataObject buildScheduledEvents(Guild guild) {
        DataArray events = DataArray.empty();

        for (ScheduledEvent event : guild.getScheduledEvents()) {
            events.add(DataObject.empty()
                    .put("id", event.getId())
                    .put("name", event.getName())
                    .put("description", event.getDescription())
                    .put("imageUrl", event.getImageUrl())
                    .put("location", event.getLocation())
                    .put("startTime", event.getStartTime().toString())
                    .put("endTime", event.getEndTime() == null ? null : event.getEndTime().toString())
                    .put("interestedCount", event.getInterestedUserCount()));
        }

        return DataObject.empty().put("events", events);
    }

    // --- Helpers ---

    private void sendJson(HttpExchange exchange, int statusCode, DataObject payload) throws IOException {
        byte[] body = payload.toJson();
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(body);
        }
    }

    /** Avoids leaking secret length/content via response-timing differences. */
    private boolean constantTimeEquals(String provided, String expected) {
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }
}
