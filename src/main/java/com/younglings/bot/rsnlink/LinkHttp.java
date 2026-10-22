package com.younglings.bot.rsnlink;

import com.sun.net.httpserver.HttpExchange;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The routes of LINK-SYSTEM.md section 5.3 under {@code /internal/rsnlink/}: {@code v1/...} mirrors the plugin API and {@code web/...} the
 * sign-in page. Whoever mounts this has already checked {@code X-Internal-Secret}; the website is the only caller and passes:
 * <ul>
 *   <li>{@code X-Link-Client-Ip}: the player's IP, used only as a keyed fingerprint for limits;</li>
 *   <li>{@code Authorization: Bearer lnk_...}: forwarded unchanged from the plugin for token routes;</li>
 *   <li>for web routes, in the JSON body, {@code browserSession} (the opaque id in the page's own cookie) and, once signed in,
 *       {@code discordId} and {@code discordName} from its verified Discord callback.</li>
 * </ul>
 * Every route is a POST with a JSON body except {@code GET v1/keys}, so nothing secret ever sits in a URL. Answers carry
 * {@code Cache-Control: no-store}. Errors are {@code {"error", "message", "retryAfter"?}}.
 */
public final class LinkHttp {
    private static final Logger log = LoggerFactory.getLogger(LinkHttp.class);
    public static final String PREFIX = "/internal/rsnlink/";
    public static final String IP_HEADER = "X-Link-Client-Ip";
    private static final int MAX_BODY_BYTES = 8 * 1024;

    /** Extra routes mounted beside the service's own (the DM fallback, the owner's abuse view). Null when the path isn't theirs. */
    public interface Extension {
        DataObject route(String method, String path, DataObject body, HttpExchange exchange);
    }

    private final LinkService service;
    private final java.util.List<Extension> extensions = new java.util.concurrent.CopyOnWriteArrayList<>();

    public LinkHttp(LinkService service) {
        this.service = service;
    }

    public void addExtension(Extension extension) {
        extensions.add(extension);
    }

    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (!path.startsWith(PREFIX)) {
            send(exchange, 404, error("not_found", "Not found"));
            return;
        }
        String route = path.substring(PREFIX.length());
        try {
            if (route.equals("v1/keys")) {
                if (!method.equals("GET")) throw new LinkException(405, "method", "Method not allowed");
                send(exchange, 200, service.keys());
                return;
            }
            if (!method.equals("POST")) throw new LinkException(405, "method", "Method not allowed");
            DataObject body = readBody(exchange);
            String ip = exchange.getRequestHeaders().getFirst(IP_HEADER);
            DataObject answer = switch (route) {
                case "v1/pair/start" -> created(service.startPairing(str(body, "rsn"), str(body, "trackingId"), client(body), ip));
                case "v1/pair/poll" -> service.poll(str(body, "pairingId"), str(body, "deviceSecret"));
                case "v1/pair/dm" -> service.dmFlow().start(str(body, "rsn"), str(body, "trackingId"), str(body, "discord"), client(body), ip);
                case "v1/pair/dm/confirm" -> service.dmFlow().confirm(str(body, "pairingId"), str(body, "deviceSecret"), str(body, "code"));
                case "resolve" -> service.resolve(str(body, "assertion"));
                case "v1/session" -> service.session(bearer(exchange), str(body, "rsn"), str(body, "trackingId"));
                case "v1/xp" -> service.xpReport(bearer(exchange), str(body, "rsn"), str(body, "trackingId"), str(body, "state"), xp10(body));
                case "v1/alias" -> service.alias(bearer(exchange), str(body, "plugin"));
                case "v1/unlink" -> service.unlink(bearer(exchange));
                case "v1/forget-me" -> service.forgetMe(bearer(exchange), str(body, "confirm"));
                case "web/code" -> service.webCode(str(body, "code"), str(body, "browserSession"), str(body, "discordId"), ip);
                case "web/pairing" -> service.webPairing(str(body, "browserSession"), str(body, "discordId"));
                case "web/confirm" -> service.webConfirm(str(body, "browserSession"), str(body, "discordId"), str(body, "discordName"), str(body, "decision"));
                case "web/links" -> service.webLinks(str(body, "discordId"));
                case "web/unlink" -> service.webUnlink(str(body, "discordId"), str(body, "linkId"));
                case "web/delete-my-data" -> service.webDeleteMyData(str(body, "discordId"), str(body, "confirm"));
                default -> {
                    DataObject fromExtension = null;
                    for (Extension e : extensions) {
                        fromExtension = e.route(method, route, body, exchange);
                        if (fromExtension != null) break;
                    }
                    if (fromExtension == null) throw new LinkException(404, "not_found", "Not found");
                    yield fromExtension;
                }
            };
            int status = answer.hasKey("__status") ? answer.getInt("__status") : 200;
            answer.remove("__status");
            send(exchange, status, answer);
        } catch (LinkException e) {
            DataObject err = error(e.code(), e.getMessage());
            if (e.retryAfter() != null) {
                err.put("retryAfter", e.retryAfter());
                exchange.getResponseHeaders().set("Retry-After", Integer.toString(e.retryAfter()));
            }
            if (e.extra() != null) e.extra().forEach(err::put);
            send(exchange, e.status(), err);
        } catch (RuntimeException e) {
            // Only the class name: a message could hold a value from the request.
            log.error("Link route {} failed: {} (cause {})", route, e.getClass().getSimpleName(), e.getCause() == null ? "-" : e.getCause().getClass().getSimpleName());
            send(exchange, 500, error("internal", "Something went wrong. Try again in a minute."));
        }
    }

    /** Marks an answer to be sent with another status than 200 (e.g. 201 for a new pairing, 202 for the DM fallback). */
    public static DataObject withStatus(DataObject answer, int status) {
        return answer.put("__status", status);
    }

    private static DataObject created(DataObject answer) {
        return withStatus(answer, 201);
    }

    private static String bearer(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        return header.substring("Bearer ".length()).strip();
    }

    private static String client(DataObject body) {
        if (!body.hasKey("client") || body.isNull("client") || !(body.opt("client").orElse(null) instanceof java.util.Map)) return null;
        DataObject c = body.getObject("client");
        return (c.getString("name", "") + " " + c.getString("version", "")).strip();
    }

    /**
     * The {@code xp} object of an XP report: skill id (as a string, JSON keys are strings) to XP in tenths. At most one entry per skill;
     * anything that isn't a whole number is dropped.
     */
    static java.util.Map<Integer, Long> xp10(DataObject body) {
        java.util.Map<Integer, Long> out = new java.util.HashMap<>();
        if (!body.hasKey("xp") || !(body.opt("xp").orElse(null) instanceof java.util.Map)) return out;
        DataObject xp = body.getObject("xp");
        for (String key : xp.keys()) {
            if (out.size() >= 29 || !key.matches("\\d{1,2}")) continue;
            Object v = xp.opt(key).orElse(null);
            if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) out.put(Integer.parseInt(key), n.longValue());
        }
        return out;
    }

    public static String str(DataObject body, String key) {
        if (!body.hasKey(key) || body.isNull(key)) return null;
        Object value = body.opt(key).orElse(null);
        return value instanceof String s ? s : value instanceof Number n ? n.toString() : null;
    }

    private static DataObject readBody(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = exchange.getRequestBody()) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_BODY_BYTES) throw new LinkException(413, "too_large", "That request is too large.");
            }
        }
        if (out.size() == 0) return DataObject.empty();
        try {
            return DataObject.fromJson(out.toByteArray());
        } catch (RuntimeException e) {
            throw LinkException.bad("bad_json", "That request isn't valid JSON.");
        }
    }

    private static DataObject error(String code, String message) {
        return DataObject.empty().put("error", code).put("message", message);
    }

    public static void send(HttpExchange exchange, int status, DataObject body) throws IOException {
        byte[] bytes = body.toJson();
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
