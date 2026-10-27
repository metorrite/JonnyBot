package com.younglings.bot.rsnlink.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.config.BotConfig;
import com.younglings.bot.internal.InternalRouteModule;
import com.younglings.bot.rsnlink.LinkDmSender;
import com.younglings.bot.rsnlink.LinkHttp;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Sends RuneLink's DM codes for runeclans-hub (T-166). The hub runs the RuneLink service, but a code DM needs a bot that shares a server with
 * the person, so the hub calls these two routes (its {@code BotDmSender}):
 * <ul>
 *   <li>{@code POST /internal/rsnlink-dm/resolve} {@code {username}} answers {@code {discordId}} (null when not found or not unique);</li>
 *   <li>{@code POST /internal/rsnlink-dm/send} {@code {discordId, rsn, code, pairingId, expiresInMinutes}} answers {@code {sent}}.</li>
 * </ul>
 * They take {@code X-RuneLink-Dm-Secret} or the older {@code X-RsLink-Dm-Secret} ({@code RUNELINK_DM_SECRET}, formerly
 * {@code RSNLINK_DM_SECRET}), never the internal API secret, so the hub can't call anything else; with
 * the setting unset they answer 401. The DM's buttons are answered by {@link HubDmButtons}, which passes them back to the hub. Nothing here
 * logs an id, username or code.
 */
@BService
public class HubDmRelay implements InternalRouteModule {
    private static final Logger log = LoggerFactory.getLogger(HubDmRelay.class);
    public static final String PREFIX = "/internal/rsnlink-dm/";
    public static final String SECRET_HEADER = "X-RuneLink-Dm-Secret";
    /** The header's name before RS Link became RuneLink, still accepted so a hub that sends it keeps working (T-659). */
    public static final String LEGACY_SECRET_HEADER = "X-RsLink-Dm-Secret";
    static final String NOT_ME = "rsnlink:hubnotme:";
    static final String STOP = "rsnlink:hubstop:";
    static final int MIN_SECRET = 32;

    /** A route's answer: an HTTP status and a JSON body. */
    record Answer(int status, DataObject body) {}

    private final String secret;
    private final JdaLinkDmSender sender = new JdaLinkDmSender(NOT_ME, STOP);

    public HubDmRelay(BotConfig config) {
        String s = config.getRuneLinkDmSecret();
        if (s != null && s.length() < MIN_SECRET) {
            log.warn("RUNELINK_DM_SECRET is shorter than {} characters; DM codes for the hub stay off", MIN_SECRET);
            s = null;
        }
        this.secret = s;
        if (s != null) log.info("RuneLink DM codes for the hub are on (/internal/rsnlink-dm/)");
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        sender.attach(event.getJda());
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public boolean authorized(HttpExchange exchange, String internalSecret) {
        return secretMatches(secret, providedSecret(exchange.getRequestHeaders()));
    }

    /** The secret the caller sent: {@link #SECRET_HEADER}, else {@link #LEGACY_SECRET_HEADER}. */
    static String providedSecret(com.sun.net.httpserver.Headers headers) {
        String provided = headers.getFirst(SECRET_HEADER);
        return provided != null ? provided : headers.getFirst(LEGACY_SECRET_HEADER);
    }

    static boolean secretMatches(String expected, String provided) {
        return expected != null && provided != null
                && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            LinkHttp.send(exchange, 405, DataObject.empty().put("error", "method_not_allowed"));
            return;
        }
        DataObject body;
        try {
            body = DataObject.fromJson(new String(exchange.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            LinkHttp.send(exchange, 400, DataObject.empty().put("error", "bad_json"));
            return;
        }
        Answer a = answer(exchange.getRequestURI().getPath().substring(PREFIX.length()), body, sender);
        LinkHttp.send(exchange, a.status(), a.body());
    }

    /** The two routes, checked and answered; pure apart from {@code sender}, so the tests drive it with a fake Discord. */
    static Answer answer(String route, DataObject body, LinkDmSender sender) {
        switch (route) {
            case "resolve" -> {
                String username = body.getString("username", "");
                if (!username.matches("[a-z0-9._]{2,32}")) return bad("bad_username");
                return new Answer(200, DataObject.empty().put("discordId", sender.resolveUsername(username).orElse(null)));
            }
            case "send" -> {
                String discordId = body.getString("discordId", "");
                String rsn = body.getString("rsn", "");
                String code = body.getString("code", "");
                String pairingId = body.getString("pairingId", "");
                long minutes = body.getLong("expiresInMinutes", 0);
                if (!discordId.matches("\\d{17,20}")) return bad("bad_discord_id");
                if (!rsn.matches("[A-Za-z0-9 _-]{1,12}")) return bad("bad_rsn");
                if (!code.matches("[A-Z0-9-]{4,16}")) return bad("bad_code");
                if (!pairingId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return bad("bad_pairing_id");
                if (minutes < 1 || minutes > 60) return bad("bad_expiry");
                return new Answer(200, DataObject.empty().put("sent", sender.sendCode(discordId, rsn, code, pairingId, minutes)));
            }
            default -> {
                return new Answer(404, DataObject.empty().put("error", "not_found"));
            }
        }
    }

    private static Answer bad(String code) {
        return new Answer(400, DataObject.empty().put("error", code));
    }
}
