package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.appeal.AppealRules;
import com.younglings.bot.appeal.AppealRules.Appeal;
import com.younglings.bot.appeal.AppealService;
import com.younglings.bot.moderation.ModCase;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * The appeal page's side of ban and mute appeals (T-033), under {@code /internal/appeals/}, for the website's signed-in visitor (the website says who:
 * the same trust as {@code /internal/me/}). It works for someone who isn't in the server, as a banned person isn't.
 * <ul>
 *   <li>{@code GET {serverId}?userId=}: whether the server takes appeals, and only this person's own cases they can appeal and their appeals.</li>
 *   <li>{@code POST {serverId}} {@code {userId, userName, caseNo, text}}: sends an appeal.</li>
 * </ul>
 * Nothing here names a moderator or anyone else.
 */
@BService
public class AppealsPublicApi implements InternalRouteModule {
    private static final Logger log = LoggerFactory.getLogger(AppealsPublicApi.class);
    private static final int MAX_BODY = 8 * 1024;

    private final AppealService service;
    private final RateLimiter reads = new RateLimiter(20, 3_000);
    private final RateLimiter sends = new RateLimiter(3, 60_000);
    private volatile JDA jda;

    public AppealsPublicApi(AppealService service) {
        this.service = service;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
    }

    @Override
    public String prefix() {
        return "/internal/appeals/";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String rest = exchange.getRequestURI().getPath().substring(prefix().length());
        String method = exchange.getRequestMethod();
        if (!rest.matches("\\d{1,20}")) {
            InternalApiServer.sendJson(exchange, 404, error("Not found"));
            return;
        }
        Guild guild = jda == null ? null : jda.getGuildById(rest);
        if (guild == null) {
            InternalApiServer.sendJson(exchange, 404, error("JonnyBot isn't in that server."));
            return;
        }
        try {
            if (method.equals("GET")) {
                Long userId = idOf(query(exchange, "userId"));
                if (userId == null) {
                    InternalApiServer.sendJson(exchange, 400, error("Sign in with Discord first."));
                    return;
                }
                if (!reads.tryAcquire(Long.toString(userId))) {
                    InternalApiServer.sendJson(exchange, 429, error("Too many requests. Try again in a moment."));
                    return;
                }
                InternalApiServer.sendJson(exchange, 200, view(guild, userId));
            } else if (method.equals("POST")) {
                DataObject body = body(exchange);
                if (body == null) {
                    InternalApiServer.sendJson(exchange, 400, error("That request couldn't be read."));
                    return;
                }
                Long userId = idOf(body.getString("userId", ""));
                if (userId == null) {
                    InternalApiServer.sendJson(exchange, 400, error("Sign in with Discord first."));
                    return;
                }
                if (!sends.tryAcquire(userId + ":" + guild.getId())) {
                    InternalApiServer.sendJson(exchange, 429, error("You've sent a few appeals just now. Wait a minute and try again."));
                    return;
                }
                String name = body.getString("userName", "").strip();
                long caseNo;
                try {
                    caseNo = Long.parseLong(body.getString("caseNo", "").strip());
                } catch (NumberFormatException e) {
                    InternalApiServer.sendJson(exchange, 400, error("Pick the case you're appealing."));
                    return;
                }
                Appeal saved = service.submit(guild, userId, name.isEmpty() ? "user " + userId : name.length() > 64 ? name.substring(0, 64) : name, caseNo,
                        body.getString("text", ""), Instant.now()).get(15, TimeUnit.SECONDS);
                InternalApiServer.sendJson(exchange, 200, DataObject.empty().put("appeal", appealJson(saved)).put("view", view(guild, userId)));
            } else {
                InternalApiServer.sendJson(exchange, 405, error("Method not allowed"));
            }
        } catch (AppealService.AppealProblem e) {
            InternalApiServer.sendJson(exchange, e.status(), error(e.getMessage()));
        } catch (java.util.concurrent.ExecutionException | CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AppealService.AppealProblem p) InternalApiServer.sendJson(exchange, p.status(), error(p.getMessage()));
            else {
                log.warn("Sending an appeal in {} failed", guild.getId(), cause);
                InternalApiServer.sendJson(exchange, 500, error("Something went wrong. Try again in a moment."));
            }
        } catch (java.util.concurrent.TimeoutException | InterruptedException e) {
            InternalApiServer.sendJson(exchange, 504, error("Discord is slow to answer. Try again in a moment."));
        }
    }

    /** What the appeal page shows this person: the server, whether it takes appeals, their cases they can appeal (with why not, when not) and their appeals. */
    DataObject view(Guild guild, long userId) {
        AppealService.Mine mine = service.mine(guild.getIdLong(), userId, Instant.now());
        DataObject server = DataObject.empty().put("id", guild.getId()).put("name", guild.getName()).put("iconUrl", guild.getIconUrl());
        if (!mine.settings().enabled()) return DataObject.empty().put("server", server).put("enabled", false).put("cases", DataArray.empty()).put("appeals", DataArray.empty());
        DataArray cases = DataArray.empty();
        for (ModCase c : mine.appealable()) {
            List<Appeal> earlier = mine.appeals().stream().filter(a -> a.caseNo() == c.caseNo()).toList();
            String refusal = AppealRules.refusal(earlier, mine.settings().cooldownDays(), Instant.now());
            cases.add(DataObject.empty().put("caseNo", Long.toString(c.caseNo())).put("action", c.action().name()).put("label", c.action().label).put("reason", c.reason())
                    .put("durationMinutes", c.durationMinutes()).put("createdAt", c.createdAt().toString()).put("canAppeal", refusal == null).put("why", refusal));
        }
        DataArray appeals = DataArray.empty();
        mine.appeals().forEach(a -> appeals.add(appealJson(a)));
        return DataObject.empty().put("server", server).put("enabled", true).put("cases", cases).put("appeals", appeals)
                .put("minText", AppealRules.MIN_TEXT).put("maxText", AppealRules.MAX_TEXT);
    }

    private static DataObject appealJson(Appeal a) {
        return DataObject.empty().put("id", Long.toString(a.id())).put("caseNo", Long.toString(a.caseNo())).put("status", a.status().name()).put("text", a.text())
                .put("createdAt", a.createdAt().toString()).put("decidedAt", a.decidedAt() == null ? null : a.decidedAt().toString()).put("note", a.note());
    }

    private static DataObject error(String message) {
        return DataObject.empty().put("error", message);
    }

    private static Long idOf(String raw) {
        return raw != null && raw.matches("\\d{1,20}") ? Long.parseUnsignedLong(raw) : null;
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
