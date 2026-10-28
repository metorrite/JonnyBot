package com.runeclans.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.premium.Billing;
import com.runeclans.bot.premium.BillingRules;
import com.runeclans.bot.premium.BillingRules.Subscription;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * The website's side of billing (T-366), under {@code /internal/billing/}. Internal secret only, like every {@link InternalRouteModule}: the
 * website verified Stripe's signature and re-read the subscription from Stripe before calling. The bot never talks to Stripe.
 * <ul>
 *   <li>{@code POST subscription} {@code {eventId, guildId, customerId, subscriptionId, status, currentPeriodEnd, cancelAtPeriodEnd, created, label?}}:
 *   one subscription change ({@link Billing#apply}); answers {@code {outcome, plan}}. Times are ISO-8601 or Unix seconds, as Stripe gives them.</li>
 *   <li>{@code GET guild/{id}?userId=}: the server's plan, its subscription on record, and {@code canManage} (that person is the server's owner),
 *   for the website's Plan page.</li>
 * </ul>
 */
@BService
public class BillingApi implements InternalRouteModule {
    private static final Logger log = LoggerFactory.getLogger(BillingApi.class);
    private static final int MAX_BODY = 16 * 1024;
    private static final int MAX_LABEL = 60;

    private final Billing billing;

    public BillingApi(Billing billing) {
        this.billing = billing;
    }

    @Override
    public String prefix() {
        return "/internal/billing/";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String route = exchange.getRequestURI().getPath().substring(prefix().length());
        String method = exchange.getRequestMethod();
        try {
            if (route.equals("subscription")) {
                if (!method.equals("POST")) {
                    InternalApiServer.sendJson(exchange, 405, error("method", "Method not allowed"));
                    return;
                }
                DataObject body = body(exchange);
                Subscription sub = body == null ? null : parse(body);
                if (sub == null) {
                    InternalApiServer.sendJson(exchange, 400, error("bad_request", "A billing event needs eventId, guildId, customerId, subscriptionId, a Stripe status and created."));
                    return;
                }
                Billing.Result result = billing.apply(sub, label(body), Instant.now());
                InternalApiServer.sendJson(exchange, 200, DataObject.empty().put("outcome", result.outcome().name().toLowerCase(java.util.Locale.ROOT))
                        .put("plan", Billing.json(billing.plan(sub.guildId(), billing.botPresent(sub.guildId()), Instant.now()))));
                return;
            }
            if (route.startsWith("guild/")) {
                if (!method.equals("GET")) {
                    InternalApiServer.sendJson(exchange, 405, error("method", "Method not allowed"));
                    return;
                }
                Long guildId = idOf(route.substring("guild/".length()));
                if (guildId == null) {
                    InternalApiServer.sendJson(exchange, 400, error("bad_request", "Not a server id."));
                    return;
                }
                InternalApiServer.sendJson(exchange, 200, guildView(guildId, userIdFrom(exchange.getRequestURI().getRawQuery()), Instant.now()));
                return;
            }
            InternalApiServer.sendJson(exchange, 404, error("not_found", "Not found"));
        } catch (RuntimeException e) {
            // the class name only: a message could carry a value from the request
            log.warn("Billing route {} failed: {}", route, e.getClass().getSimpleName());
            InternalApiServer.sendJson(exchange, 500, error("failed", "Something went wrong. Try again in a moment."));
        }
    }

    /** The plan, the subscription on record, and whether {@code userId} (the signed-in person, from the website's session) may buy or manage it. */
    DataObject guildView(long guildId, Long userId, Instant now) {
        Subscription sub = billing.subscription(guildId).orElse(null);
        DataObject subscription = sub == null ? null : DataObject.empty().put("customerId", sub.customerId()).put("subscriptionId", sub.subscriptionId())
                .put("status", sub.status()).put("currentPeriodEnd", sub.currentPeriodEnd() == null ? null : sub.currentPeriodEnd().toString())
                .put("cancelAtPeriodEnd", sub.cancelAtPeriodEnd());
        return DataObject.empty().put("guildId", Long.toString(guildId)).put("plan", Billing.json(billing.plan(guildId, billing.botPresent(guildId), now)))
                .put("subscription", subscription).put("canManage", userId != null && billing.mayManage(guildId, userId));
    }

    /** {@code userId=} from the query string, or null. */
    static Long userIdFrom(String rawQuery) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            if (pair.startsWith("userId=")) return idOf(pair.substring("userId=".length()));
        }
        return null;
    }

    /** The event from the request, or null when anything needed is missing or malformed. */
    static Subscription parse(DataObject body) {
        String eventId = text(body, "eventId", 255);
        Long guildId = idOf(body.getString("guildId", ""));
        String customerId = text(body, "customerId", 255);
        String subscriptionId = text(body, "subscriptionId", 255);
        String status = BillingRules.cleanStatus(body.getString("status", null));
        Instant created = time(body, "created");
        if (eventId == null || guildId == null || customerId == null || subscriptionId == null || status == null || created == null) return null;
        if (body.hasKey("currentPeriodEnd") && !body.isNull("currentPeriodEnd") && time(body, "currentPeriodEnd") == null) return null;
        return new Subscription(guildId, customerId, subscriptionId, status, time(body, "currentPeriodEnd"), body.getBoolean("cancelAtPeriodEnd", false), created, eventId);
    }

    private static String label(DataObject body) {
        String raw = body.getString("label", "");
        String label = raw == null ? "" : raw.strip();
        return label.length() > MAX_LABEL ? label.substring(0, MAX_LABEL) : label;
    }

    /** A Stripe id or event id: printable, no spaces, at most {@code max} long. */
    private static String text(DataObject body, String key, int max) {
        String raw = body.getString(key, null);
        if (raw == null) return null;
        raw = raw.strip();
        return !raw.isEmpty() && raw.length() <= max && raw.matches("[A-Za-z0-9_\\-.:]+") ? raw : null;
    }

    /** ISO-8601, or Unix seconds as a number or a string. Null when absent or unreadable. */
    static Instant time(DataObject body, String key) {
        if (!body.hasKey(key) || body.isNull(key)) return null;
        Object raw = body.get(key);
        try {
            if (raw instanceof Number n) return Instant.ofEpochSecond(n.longValue());
            String s = String.valueOf(raw).strip();
            if (s.matches("\\d{1,12}")) return Instant.ofEpochSecond(Long.parseLong(s));
            return Instant.parse(s);
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    private static Long idOf(String raw) {
        return raw != null && raw.matches("\\d{17,20}") ? Long.parseUnsignedLong(raw) : null;
    }

    private static DataObject error(String code, String message) {
        return DataObject.empty().put("error", code).put("message", message);
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
