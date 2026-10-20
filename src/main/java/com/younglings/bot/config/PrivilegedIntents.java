package com.younglings.bot.config;

import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which of Discord's privileged gateway intents (server members, presences, message content) the bot may ask for. Asking for one that is
 * not switched on in the Developer Portal makes Discord refuse the login, so the bot would not start at all. Before connecting, the bot
 * asks Discord which of them its application has on (the {@code flags} of {@code GET /applications/@me}) and leaves out the ones that are
 * off, logging which features are affected. If Discord cannot be asked, every intent is requested exactly as before.
 * <p>
 * Features that read message text check {@link #messageContent()} and say so in the log instead of silently doing nothing.
 */
public final class PrivilegedIntents {
    private static final Logger log = LoggerFactory.getLogger(PrivilegedIntents.class);

    // Application flags, from Discord's documentation: each intent has a normal bit and a "limited" bit (bots in fewer than 100 servers).
    static final long PRESENCE = (1L << 12) | (1L << 13);
    static final long GUILD_MEMBERS = (1L << 14) | (1L << 15);
    static final long MESSAGE_CONTENT = (1L << 18) | (1L << 19);

    private static volatile boolean messageContent = true;
    private static volatile boolean members = true;
    private static volatile boolean presence = true;

    private PrivilegedIntents() {}

    /** Whether message text can be read. True until a check shows the portal switch is off. */
    public static boolean messageContent() {
        return messageContent;
    }

    /** Whether member presence (who is online) is available. True until a check shows the portal switch is off; the clan site then hides its online list. */
    public static boolean presence() {
        return presence;
    }

    /** Whether the server member list and join/leave events are available. */
    public static boolean members() {
        return members;
    }

    /** {@code wanted} without the privileged intents the application has not switched on. Asks Discord once; never throws. */
    public static Set<GatewayIntent> filter(String token, Set<GatewayIntent> wanted) {
        Long flags = fetchFlags(token);
        return filter(flags, wanted);
    }

    static Set<GatewayIntent> filter(Long flags, Set<GatewayIntent> wanted) {
        if (flags == null) {
            log.info("Could not read the application's intent switches from Discord; requesting every intent as configured.");
            return wanted;
        }
        EnumSet<GatewayIntent> result = EnumSet.copyOf(wanted);
        drop(result, flags, MESSAGE_CONTENT, GatewayIntent.MESSAGE_CONTENT, "Message Content",
                "message logs, Highlights, automatic replies, automod word filters and custom commands cannot read message text");
        drop(result, flags, GUILD_MEMBERS, GatewayIntent.GUILD_MEMBERS, "Server Members", "joins, leaves, autoroles and member tracking will not work");
        drop(result, flags, PRESENCE, GatewayIntent.GUILD_PRESENCES, "Presence", "the clan site's online list is hidden");
        messageContent = result.contains(GatewayIntent.MESSAGE_CONTENT);
        members = result.contains(GatewayIntent.GUILD_MEMBERS);
        presence = result.contains(GatewayIntent.GUILD_PRESENCES);
        return result;
    }

    private static void drop(EnumSet<GatewayIntent> intents, long flags, long mask, GatewayIntent intent, String name, String effect) {
        if (!intents.contains(intent) || (flags & mask) != 0) return;
        intents.remove(intent);
        log.warn("The {} intent is switched OFF for this bot in the Discord Developer Portal, so it starts without it: {}. "
                + "Turn it on under Bot > Privileged Gateway Intents, then restart.", name, effect);
    }

    private static final Pattern FLAGS = Pattern.compile("\"flags\"\\s*:\\s*(\\d+)");

    /** The application's flags, or null when Discord could not be asked. The token is only ever sent to Discord, never logged. */
    private static Long fetchFlags(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://discord.com/api/v10/applications/@me"))
                    .timeout(Duration.ofSeconds(8)).header("Authorization", "Bot " + token).header("User-Agent", "JonnyBot (intent check)").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            return parseFlags(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    static Long parseFlags(String json) {
        Matcher m = FLAGS.matcher(json == null ? "" : json);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }
}
