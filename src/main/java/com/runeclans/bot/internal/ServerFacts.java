package com.runeclans.bot.internal;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * What the website vouches for about the signed-in person's staff standing in other Discord servers (N5, the site admin's staff-server rule:
 * "members of this server with at least this permission"). Discord gives the website each server's permissions at sign-in (the {@code guilds}
 * scope); the bot can't see them for a server it isn't in, so the website signs them. Unlike {@link ActorFacts} these are not about the server a
 * request names, so they ride on every call the person makes, the site viewer's included.
 * <p>
 * The contract (the website signs in {@code packages/api-client/src/serverFactsLogic.ts}; neither side changes it alone):
 * <ul>
 *   <li>{@code X-RC-Servers}: base64url (no padding) of compact JSON
 *   {@code {"v":1,"userId":"<id>","servers":{"<server id>":"<permission bits, decimal>",...},"iat":<unix s>,"exp":<unix s>}}. Only servers where
 *   the person owns the server or holds a staff permission are listed (at most {@link #MAX_SERVERS}); an owner's bits include Administrator.</li>
 *   <li>{@code X-RC-Servers-Sig}: lowercase hex HMAC-SHA256 over the exact {@code X-RC-Servers} string, keyed with the internal secret</li>
 *   <li>checked here: the signature (constant time), {@code v == 1}, {@code iat <= now + 60}, {@code exp > now}, {@code exp - iat <= 300}, and
 *   that {@code userId} is the person the request is for</li>
 * </ul>
 * Missing or invalid facts are simply no facts (no staff servers): they only ever add access, so refusing them loses nothing but that.
 */
public record ServerFacts(long userId, Map<Long, Long> servers) {
    public static final String HEADER = "X-RC-Servers";
    public static final String SIG_HEADER = "X-RC-Servers-Sig";
    static final int MAX_SERVERS = 200;

    public ServerFacts {
        servers = Map.copyOf(servers);
    }

    /** The staff servers from the two headers, or an empty map when they are missing, unsigned, expired or for someone else. */
    public static Map<Long, Long> serversOf(String facts, String signature, String secret, long userId, long nowSeconds) {
        ServerFacts verified = verify(facts, signature, secret, userId, nowSeconds);
        return verified == null ? Map.of() : verified.servers();
    }

    /** The facts, or null when they don't check out. */
    public static ServerFacts verify(String facts, String signature, String secret, long userId, long nowSeconds) {
        if (facts == null || facts.isBlank() || signature == null || signature.isBlank() || secret == null || secret.isEmpty() || facts.length() > 16_384) return null;
        String expected = ActorFacts.sign(facts, secret);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), signature.strip().getBytes(StandardCharsets.US_ASCII))) return null;
        try {
            DataObject json = DataObject.fromJson(Base64.getUrlDecoder().decode(facts));
            if (json.getInt("v", 0) != 1) return null;
            String rawUser = json.getString("userId", "");
            if (!rawUser.matches("[0-9]{15,20}") || Long.parseLong(rawUser) != userId) return null;
            long iat = json.getLong("iat");
            long exp = json.getLong("exp");
            if (iat > nowSeconds + ActorFacts.CLOCK_SKEW_SECONDS || exp <= nowSeconds || exp - iat > ActorFacts.MAX_LIFETIME_SECONDS) return null;
            DataObject raw = json.getObject("servers");
            Map<Long, Long> servers = new HashMap<>();
            for (String key : raw.keys()) {
                if (servers.size() >= MAX_SERVERS) break;
                String bits = raw.getString(key, "");
                if (!key.matches("[0-9]{15,20}") || !bits.matches("[0-9]{1,20}")) continue;
                servers.put(Long.parseLong(key), Long.parseUnsignedLong(bits));
            }
            return new ServerFacts(userId, servers);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The {@code X-RC-Servers} value for these facts (what the website builds); for tests and tools. */
    public static String encode(DataObject json) {
        return ActorFacts.encode(json);
    }
}
