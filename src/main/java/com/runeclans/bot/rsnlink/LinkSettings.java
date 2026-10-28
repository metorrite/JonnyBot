package com.runeclans.bot.rsnlink;

import java.util.Base64;
import java.util.function.Function;

/**
 * Everything the link service is configured with, read from the environment (LINK-SYSTEM.md section 6.1). The service is off unless
 * {@code LINK_DATABASE_URL} is set; once it is, every key must be present and at least 32 bytes, or the service refuses to start.
 * Keys are base64 (standard or URL-safe, padding optional). None of the values is ever logged or put in an error message: only the
 * names of the variables that are wrong.
 *
 * @param databaseUrl   {@code postgresql://user:password@host:port/db} for the link database (never the clan database)
 * @param encKey        AES-256-GCM key for Discord ids ({@code LINK_ENC_KEY})
 * @param encKeyOld     the previous encryption key during a rotation, or null ({@code LINK_ENC_KEY_OLD})
 * @param lookupKey     HMAC key for every lookup column ({@code LINK_LOOKUP_KEY})
 * @param aliasKey      HMAC key for per-plugin aliases ({@code LINK_ALIAS_KEY}); rotating it changes every alias
 * @param signingSeed   32-byte Ed25519 seed for alias assertions ({@code LINK_SIGNING_KEY})
 * @param publicBaseUrl the address players type, e.g. {@code http://localhost:3000} ({@code LINK_PUBLIC_BASE_URL})
 * @param schema        the database schema, {@code rsnlink} (tests use their own)
 */
public record LinkSettings(String databaseUrl, byte[] encKey, byte[] encKeyOld, byte[] lookupKey, byte[] aliasKey, byte[] signingSeed,
                           String publicBaseUrl, String schema) {
    public static final String DEFAULT_SCHEMA = "rsnlink";
    public static final int MIN_KEY_BYTES = 32;

    /** Thrown when the service is switched on but misconfigured. The message names variables, never values. */
    public static final class InvalidSettings extends RuntimeException {
        public InvalidSettings(String message) {
            super(message);
        }
    }

    /**
     * The settings, or null when the service is simply not switched on ({@code LINK_DATABASE_URL} unset).
     *
     * @param env looks a variable up (environment first, then the git-ignored .env file), returning null when unset
     */
    public static LinkSettings fromEnv(Function<String, String> env) {
        String url = trimmed(env.apply("LINK_DATABASE_URL"));
        if (url == null) return null;
        StringBuilder problems = new StringBuilder();
        byte[] enc = key(env, "LINK_ENC_KEY", true, problems);
        byte[] old = key(env, "LINK_ENC_KEY_OLD", false, problems);
        byte[] lookup = key(env, "LINK_LOOKUP_KEY", true, problems);
        byte[] alias = key(env, "LINK_ALIAS_KEY", true, problems);
        byte[] signing = key(env, "LINK_SIGNING_KEY", true, problems);
        if (signing != null && signing.length != 32) problems.append(" LINK_SIGNING_KEY must be exactly 32 bytes (an Ed25519 seed).");
        String base = trimmed(env.apply("LINK_PUBLIC_BASE_URL"));
        if (base == null || !(base.startsWith("https://") || base.startsWith("http://localhost") || base.startsWith("http://127.0.0.1"))) {
            problems.append(" LINK_PUBLIC_BASE_URL must be an https address (plain http only for localhost).");
        }
        if (!problems.isEmpty()) throw new InvalidSettings("The link service is switched on but not set up:" + problems);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (lookup != null && alias != null && java.util.Arrays.equals(lookup, alias)) {
            throw new InvalidSettings("LINK_ALIAS_KEY must be different from LINK_LOOKUP_KEY.");
        }
        return new LinkSettings(url, enc, old, lookup, alias, signing, base, DEFAULT_SCHEMA);
    }

    private static byte[] key(Function<String, String> env, String name, boolean required, StringBuilder problems) {
        String raw = trimmed(env.apply(name));
        if (raw == null) {
            if (required) problems.append(' ').append(name).append(" is missing.");
            return null;
        }
        byte[] bytes;
        try {
            String normal = raw.replace('-', '+').replace('_', '/');
            bytes = Base64.getDecoder().decode(normal + "=".repeat((4 - normal.length() % 4) % 4));
        } catch (IllegalArgumentException e) {
            problems.append(' ').append(name).append(" isn't base64.");
            return null;
        }
        if (bytes.length < MIN_KEY_BYTES) {
            problems.append(' ').append(name).append(" is shorter than ").append(MIN_KEY_BYTES).append(" bytes.");
            return null;
        }
        return bytes;
    }

    private static String trimmed(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** The same settings with another schema (tests keep their rows apart from a developer's own). */
    public LinkSettings withSchema(String otherSchema) {
        return new LinkSettings(databaseUrl, encKey, encKeyOld, lookupKey, aliasKey, signingSeed, publicBaseUrl, otherSchema);
    }

    /** Hides every value: a record's default toString would print the keys. */
    @Override
    public String toString() {
        return "LinkSettings[schema=" + schema + ", keys=hidden]";
    }
}
