package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * A small, private HTTP API for the companion website (younglings-website) to read live guild
 * data and perform a couple of profile actions on a member's behalf, server-side. This is
 * deliberately NOT a public API — every request must carry the correct {@code X-Internal-Secret}
 * header, and it's meant to be called only from the website's own backend, never from a browser
 * directly.
 * <p>
 * <b>Trust boundary:</b> the shared secret authenticates that a request genuinely comes from the
 * website's backend — it says nothing about which end user it's acting on behalf of. Every write
 * endpoint here takes a {@code userId} and just performs the action on that member, trusting the
 * caller. The website is responsible for always sourcing {@code userId} from its own
 * server-side-verified login session (never from client-supplied input), or this would let anyone
 * who can reach the endpoint modify an arbitrary member.
 * <p>
 * Entirely opt-in: if {@link BotConfig#getInternalApiSecret()} or {@link BotConfig#getGuildId()}
 * aren't configured, the server simply never starts — the bot's core functionality never depends
 * on this.
 * <p>
 * Why the read endpoints live in the bot at all (rather than the website talking to Discord
 * directly): Discord only pushes member presence (online/idle/dnd/offline) over a persistent
 * Gateway connection, the kind this bot already maintains. A website's backend can't cheaply get
 * that on its own without running a second, redundant Gateway connection — so it asks the bot,
 * which already knows.
 * <p>
 * {@link JDA} isn't available yet when {@code @BService}s are normally constructed at startup — it
 * only exists once {@link com.younglings.bot.Bot#createJDA} actually runs. So instead of taking it
 * as a constructor parameter (which would fail immediately, before the bot even logs in), this
 * waits for {@link InjectedJDAEvent}, which BotCommands fires once JDA is ready, and starts the
 * HTTP server at that point.
 */
@BService
public class InternalApiServer {
    private static final Logger log = LoggerFactory.getLogger(InternalApiServer.class);
    private static final String SECRET_HEADER = "X-Internal-Secret";
    private static final int MAX_NICKNAME_LENGTH = 32; // Discord's own limit

    private final BotConfig botConfig;
    private JDA jda;

    public InternalApiServer(BotConfig botConfig) {
        this.botConfig = botConfig;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
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

            server.createContext("/internal/online-members", exchange -> handleOnlineMembers(exchange, secret, guildId));
            server.createContext("/internal/events", exchange -> handleEvents(exchange, secret, guildId));
            server.createContext("/internal/member", exchange -> handleGetMember(exchange, secret, guildId));
            server.createContext("/internal/nickname", exchange -> handleSetNickname(exchange, secret, guildId));
            server.createContext("/internal/color-role", exchange -> handleSetColorRole(exchange, secret, guildId));

            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            log.info("Internal API listening on port {}", port);
        } catch (IOException e) {
            log.error("Failed to start internal API server", e);
        }
    }

    // --- Auth + guild resolution shared by every endpoint ---

    /** Checks method + secret + guild availability, sending the appropriate error response and returning {@code null} if any fail. */
    private Guild authorize(HttpExchange exchange, String expectedSecret, long guildId, String requiredMethod) throws IOException {
        if (!requiredMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, DataObject.empty().put("error", "Method not allowed"));
            return null;
        }

        String provided = exchange.getRequestHeaders().getFirst(SECRET_HEADER);
        if (provided == null || !constantTimeEquals(provided, expectedSecret)) {
            sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
            return null;
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            sendJson(exchange, 503, DataObject.empty().put("error", "Guild not available yet"));
            return null;
        }

        return guild;
    }

    // --- Read endpoints ---

    private void handleOnlineMembers(HttpExchange exchange, String secret, long guildId) throws IOException {
        try {
            Guild guild = authorize(exchange, secret, guildId, "GET");
            if (guild == null) return;

            sendJson(exchange, 200, buildOnlineMembers(guild));
        } catch (Exception e) {
            log.error("Internal API /online-members request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    private void handleEvents(HttpExchange exchange, String secret, long guildId) throws IOException {
        try {
            Guild guild = authorize(exchange, secret, guildId, "GET");
            if (guild == null) return;

            sendJson(exchange, 200, buildScheduledEvents(guild));
        } catch (Exception e) {
            log.error("Internal API /events request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    private void handleGetMember(HttpExchange exchange, String secret, long guildId) throws IOException {
        try {
            Guild guild = authorize(exchange, secret, guildId, "GET");
            if (guild == null) return;

            String userIdRaw = queryParam(exchange, "userId");
            if (userIdRaw == null) {
                sendJson(exchange, 400, DataObject.empty().put("error", "Missing userId query parameter"));
                return;
            }

            Member member = guild.getMemberById(Long.parseLong(userIdRaw));
            if (member == null) {
                sendJson(exchange, 404, DataObject.empty().put("error", "Member not found in guild"));
                return;
            }

            sendJson(exchange, 200, buildMemberInfo(member));
        } catch (NumberFormatException e) {
            sendJson(exchange, 400, DataObject.empty().put("error", "userId must be numeric"));
        } catch (Exception e) {
            log.error("Internal API /member request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    // --- Write endpoints ---

    private void handleSetNickname(HttpExchange exchange, String secret, long guildId) throws IOException {
        try {
            Guild guild = authorize(exchange, secret, guildId, "POST");
            if (guild == null) return;

            DataObject body = readJsonBody(exchange);
            Member member = guild.getMemberById(body.getLong("userId"));
            if (member == null) {
                sendJson(exchange, 404, DataObject.empty().put("error", "Member not found in guild"));
                return;
            }

            String nickname = body.isNull("nickname") ? null : body.getString("nickname").trim();
            if (nickname != null && nickname.isEmpty()) nickname = null;
            if (nickname != null && nickname.length() > MAX_NICKNAME_LENGTH) {
                sendJson(exchange, 400, DataObject.empty().put("error",
                        "Nickname must be " + MAX_NICKNAME_LENGTH + " characters or fewer"));
                return;
            }

            member.modifyNickname(nickname).complete();
            log.info("Set nickname for member {} in guild {}", member.getId(), guildId);
            sendJson(exchange, 200, DataObject.empty().put("nickname", nickname));
        } catch (Exception e) {
            log.error("Internal API /nickname request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error",
                    "Failed to change nickname — the bot may not have permission here"));
        }
    }

    private void handleSetColorRole(HttpExchange exchange, String secret, long guildId) throws IOException {
        try {
            Guild guild = authorize(exchange, secret, guildId, "POST");
            if (guild == null) return;

            DataObject body = readJsonBody(exchange);
            Member member = guild.getMemberById(body.getLong("userId"));
            if (member == null) {
                sendJson(exchange, 404, DataObject.empty().put("error", "Member not found in guild"));
                return;
            }

            String colorKey = body.isNull("color") ? null : body.getString("color");
            if (colorKey != null && !ColorRoleCatalog.isValidKey(colorKey)) {
                sendJson(exchange, 400, DataObject.empty().put("error", "Unknown color: " + colorKey));
                return;
            }

            List<Role> toRemove = new ArrayList<>();
            for (Role role : member.getRoles()) {
                if (ColorRoleCatalog.isColorRoleName(role.getName())) toRemove.add(role);
            }

            List<Role> toAdd = new ArrayList<>();
            if (colorKey != null) {
                toAdd.add(ensureColorRole(guild, colorKey));
            }

            if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
                guild.modifyMemberRoles(member, toAdd, toRemove).complete();
            }

            log.info("Set color role '{}' for member {} in guild {}", colorKey, member.getId(), guildId);
            sendJson(exchange, 200, DataObject.empty().put("colorRole", colorKey));
        } catch (Exception e) {
            log.error("Internal API /color-role request failed", e);
            sendJson(exchange, 500, DataObject.empty().put("error",
                    "Failed to change color role — the bot may not have permission here"));
        }
    }

    private Role ensureColorRole(Guild guild, String colorKey) {
        String roleName = ColorRoleCatalog.roleName(colorKey);
        List<Role> existing = guild.getRolesByName(roleName, true);
        if (!existing.isEmpty()) return existing.getFirst();

        return guild.createRole()
                .setName(roleName)
                .setColor(ColorRoleCatalog.colorFor(colorKey))
                .setMentionable(false)
                .setHoisted(false)
                .complete();
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

    private DataObject buildMemberInfo(Member member) {
        String colorRoleKey = null;
        for (Role role : member.getRoles()) {
            String key = ColorRoleCatalog.keyFromRoleName(role.getName());
            if (key != null) {
                colorRoleKey = key;
                break;
            }
        }

        return DataObject.empty()
                .put("id", member.getId())
                .put("username", member.getUser().getName())
                .put("nickname", member.getNickname())
                .put("displayName", member.getEffectiveName())
                .put("avatarUrl", member.getEffectiveAvatarUrl())
                .put("colorRole", colorRoleKey);
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

    private DataObject readJsonBody(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            return DataObject.fromJson(body);
        }
    }

    private String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;

        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** Avoids leaking secret length/content via response-timing differences. */
    private boolean constantTimeEquals(String provided, String expected) {
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }
}
