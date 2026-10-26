package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.linkedroles.LinkedRoles;
import com.younglings.bot.linkedroles.LinkedRoles.Shared;
import com.younglings.bot.linkedroles.LinkedRolesService;
import com.younglings.bot.linkedroles.RoleMetadata.Profile;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * The website's side of Discord Linked Roles (T-LR), under {@code /internal/linked-roles/}, for the person who just signed in on
 * {@code /linked-roles} (the website says who, from its verified OAuth callback: the same trust as {@code /internal/me/}). Only that person's own
 * data is read or sent. Every route is a POST with a JSON body, so no token ever sits in a URL or a log line:
 * <ul>
 *   <li>{@code connect} {@code {userId, accessToken, refreshToken, expiresIn, rsLinkRsn?, rsLinkSince?}}: keeps the tokens (sealed) and sends
 *       the person's metadata to Discord; answers what was shared.</li>
 *   <li>{@code status} {@code {userId}}: whether they are connected and what is shared, worked out fresh, and whether RS Link names must be
 *       confirmed first ({@code requireConfirmed}; {@code rsLinkUnconfirmed} names one that isn't counted yet, T-552).</li>
 *   <li>{@code unlink} {@code {userId}}: clears what Discord has, revokes and forgets the tokens.</li>
 * </ul>
 * Answers 404 {@code {"error":"off"}} while Linked Roles is switched off.
 */
@BService
public class LinkedRolesApi implements InternalRouteModule {
    private static final Logger log = LoggerFactory.getLogger(LinkedRolesApi.class);
    private static final int MAX_BODY = 16 * 1024;

    private final LinkedRoles roles;
    private final RateLimiter calls = new RateLimiter(10, 60_000);

    public LinkedRolesApi(LinkedRolesService service) {
        this.roles = service.roles();
    }

    @Override
    public String prefix() {
        return "/internal/linked-roles/";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String route = exchange.getRequestURI().getPath().substring(prefix().length());
        if (!exchange.getRequestMethod().equals("POST")) {
            InternalApiServer.sendJson(exchange, 405, error("method", "Method not allowed"));
            return;
        }
        if (!roles.enabled()) {
            InternalApiServer.sendJson(exchange, 404, error("off", "Linked Roles isn't switched on."));
            return;
        }
        DataObject body = body(exchange);
        Long userId = body == null ? null : idOf(body.getString("userId", ""));
        if (userId == null) {
            InternalApiServer.sendJson(exchange, 400, error("bad_request", "Sign in with Discord first."));
            return;
        }
        if (!calls.tryAcquire(Long.toString(userId))) {
            InternalApiServer.sendJson(exchange, 429, error("rate_limited", "Too many tries. Wait a minute and try again."));
            return;
        }
        try {
            switch (route) {
                case "connect" -> {
                    String accessToken = body.getString("accessToken", null);
                    String refreshToken = body.getString("refreshToken", null);
                    long expiresIn = body.getLong("expiresIn", 604800);
                    String rsn = body.getString("rsLinkRsn", null);
                    Instant since = instant(body.getString("rsLinkSince", null));
                    Shared shared = AdminReads.withinBudget(() -> roles.connect(userId, accessToken, refreshToken, expiresIn, rsn, since));
                    InternalApiServer.sendJson(exchange, 200, view(Optional.of(shared)));
                }
                case "status" -> InternalApiServer.sendJson(exchange, 200, view(AdminReads.withinBudget(() -> roles.status(userId)))
                        .put("requireConfirmed", roles.requireConfirmed()));
                case "unlink" -> InternalApiServer.sendJson(exchange, 200, DataObject.empty().put("unlinked", AdminReads.withinBudget(() -> roles.unlink(userId))));
                default -> InternalApiServer.sendJson(exchange, 404, error("not_found", "Not found"));
            }
        } catch (LinkedRoles.Problem p) {
            InternalApiServer.sendJson(exchange, p.status(), error(p.code(), p.getMessage()));
        } catch (StaleCache.Late late) {
            InternalApiServer.sendJson(exchange, 503, error("slow", "Discord is slow right now, so this is still being done. Check again in a minute."));
        } catch (RuntimeException e) {
            // the class name only: a message could carry a value from the request
            log.warn("Linked roles route {} failed: {}", route, e.getClass().getSimpleName());
            InternalApiServer.sendJson(exchange, 500, error("failed", "Something went wrong. Try again in a moment."));
        }
    }

    static DataObject view(Optional<Shared> shared) {
        if (shared.isEmpty()) return DataObject.empty().put("connected", false);
        Shared s = shared.get();
        Profile p = s.profile();
        DataObject values = DataObject.empty();
        s.values().forEach(values::put);
        return DataObject.empty().put("connected", true).put("rsn", p.rsn()).put("totalLevel", p.totalLevel()).put("combatLevel", p.combatLevel())
                .put("clanMember", p.clanMember()).put("linkedSince", p.linkedSince() == null ? null : p.linkedSince().toString())
                .put("values", values).put("pushedAt", s.pushedAt() == null ? null : s.pushedAt().toString())
                .put("rsLinkUnconfirmed", s.unconfirmedRsLink());
    }

    private static DataObject error(String code, String message) {
        return DataObject.empty().put("error", code).put("message", message);
    }

    private static Instant instant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Long idOf(String raw) {
        return raw != null && raw.matches("\\d{17,20}") ? Long.parseUnsignedLong(raw) : null;
    }

    private static DataObject body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) return null;
            return DataObject.fromJson(bytes);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
