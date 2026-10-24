package com.younglings.bot.internal;

import net.dv8tion.jda.api.utils.data.DataObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * What the website vouches for about the signed-in person in a server the bot is NOT in (a bot-less clan site): their Discord id, the server,
 * and whether Discord told the website they own it or may manage it (Administrator or Manage Server). The bot can't look any of that up without
 * being in the server, so the website signs it, and these facts are used ONLY when the bot isn't there; where the bot is, it asks Discord itself.
 * <p>
 * The contract (lane A1 builds the website side against it, so it must not change without both sides):
 * <ul>
 *   <li>{@code X-RC-Actor}: base64url (no padding) of compact JSON
 *   {@code {"v":1,"userId":"<id>","guildId":"<id>","admin":bool,"owner":bool,"iat":<unix s>,"exp":<unix s>}}</li>
 *   <li>{@code X-RC-Actor-Sig}: lowercase hex HMAC-SHA256 over the exact {@code X-RC-Actor} string, keyed with the internal secret (the same one
 *   as {@code X-Internal-Secret})</li>
 *   <li>checked here: the signature (constant time), {@code v == 1}, {@code iat <= now + 60}, {@code exp > now}, {@code exp - iat <= 300}, and the
 *   server is the one the request is about</li>
 * </ul>
 */
public record ActorFacts(long userId, long guildId, boolean admin, boolean owner, long iat, long exp) {
    public static final String HEADER = "X-RC-Actor";
    public static final String SIG_HEADER = "X-RC-Actor-Sig";
    static final long MAX_LIFETIME_SECONDS = 300;
    static final long CLOCK_SKEW_SECONDS = 60;

    /** Why the facts were refused, in words the website can show (always a 403). */
    public static final class Invalid extends Exception {
        Invalid(String message) {
            super(message);
        }
    }

    /**
     * The facts from the two headers, checked against {@code secret}, the server the request names and the time now ({@code nowSeconds}, unix).
     * Throws {@link Invalid} for anything missing, malformed, unsigned, expired or about another server.
     */
    public static ActorFacts verify(String actor, String signature, String secret, long routeGuildId, long nowSeconds) throws Invalid {
        if (actor == null || actor.isBlank() || signature == null || signature.isBlank()) {
            throw new Invalid("The bot isn't in this server, so the website must vouch for who you are there. Sign in again and retry.");
        }
        if (secret == null || secret.isEmpty()) throw new Invalid("Signed sign-in facts can't be checked: the bot has no internal secret.");
        String expected = sign(actor, secret);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), signature.strip().getBytes(StandardCharsets.US_ASCII))) {
            throw new Invalid("The website's signature on your sign-in facts doesn't match. Sign in again and retry.");
        }

        DataObject json;
        try {
            json = DataObject.fromJson(Base64.getUrlDecoder().decode(actor));
        } catch (RuntimeException e) {
            throw new Invalid("Your sign-in facts couldn't be read. Sign in again and retry.");
        }
        try {
            if (json.getInt("v", 0) != 1) throw new Invalid("Your sign-in facts are in a version the bot doesn't know.");
            long userId = snowflake(json, "userId");
            long guildId = snowflake(json, "guildId");
            if (!json.hasKey("admin") || !json.hasKey("owner") || !json.hasKey("iat") || !json.hasKey("exp")) {
                throw new Invalid("Your sign-in facts are incomplete. Sign in again and retry.");
            }
            boolean admin = json.getBoolean("admin");
            boolean owner = json.getBoolean("owner");
            long iat = json.getLong("iat");
            long exp = json.getLong("exp");
            if (iat > nowSeconds + CLOCK_SKEW_SECONDS) throw new Invalid("Your sign-in facts are dated in the future. Check the website's clock.");
            if (exp <= nowSeconds) throw new Invalid("Your sign-in facts have expired. Reload the page and retry.");
            if (exp - iat > MAX_LIFETIME_SECONDS) throw new Invalid("Your sign-in facts last too long to be trusted.");
            if (guildId != routeGuildId) throw new Invalid("Your sign-in facts are for a different server.");
            return new ActorFacts(userId, guildId, admin, owner, iat, exp);
        } catch (Invalid e) {
            throw e;
        } catch (RuntimeException e) {
            throw new Invalid("Your sign-in facts couldn't be read. Sign in again and retry.");
        }
    }

    /** Lowercase hex HMAC-SHA256 of {@code actor} under {@code secret}: what {@code X-RC-Actor-Sig} must be. */
    public static String sign(String actor, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(actor.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /** The {@code X-RC-Actor} value for these facts (what the website builds); for tests and tools. */
    public static String encode(DataObject json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static long snowflake(DataObject json, String key) throws Invalid {
        String raw = json.getString(key, "");
        if (!raw.matches("[0-9]{15,20}")) throw new Invalid("Your sign-in facts don't name a Discord " + (key.equals("userId") ? "account" : "server") + ".");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new Invalid("Your sign-in facts don't name a Discord " + (key.equals("userId") ? "account" : "server") + ".");
        }
    }
}
