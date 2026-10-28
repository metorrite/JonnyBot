package com.runeclans.bot.plugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The plugin's secret: a random token the player copies into the plugin once. Only its SHA-256 hash is stored, so a database leak does not
 * hand out working tokens, and the token is shown in full only at the moment it is created. A token that was lost is revoked and replaced.
 */
public final class PluginTokens {
    public static final String PREFIX = "jbp_";
    public static final int MAX_ACTIVE_PER_USER = 5;
    public static final int MAX_LABEL = 40;

    private static final int RANDOM_BYTES = 32;

    private PluginTokens() {}

    /** A new token, e.g. {@code jbp_Kx3...}: the prefix makes it recognisable (and findable by secret scanners), then 43 random URL-safe characters. */
    public static String generate(SecureRandom random) {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** What is stored and looked up instead of the token itself. */
    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** Whether text could be one of our tokens, so obvious junk is refused before touching the database. */
    public static boolean looksLikeToken(String text) {
        return text != null && text.length() == PREFIX.length() + 43 && text.startsWith(PREFIX) && text.substring(PREFIX.length()).matches("[A-Za-z0-9_-]{43}");
    }

    /** A label for the token, tidied: trimmed, at most {@link #MAX_LABEL} characters, "Plugin" if empty. */
    public static String cleanLabel(String label) {
        String l = label == null ? "" : label.strip().replaceAll("\\s+", " ");
        if (l.isEmpty()) return "Plugin";
        return l.length() > MAX_LABEL ? l.substring(0, MAX_LABEL) : l;
    }

    /** "jbp_Kx3a…" : enough to tell tokens apart in a list without revealing them. */
    public static String preview(String token) {
        return token.length() <= PREFIX.length() + 4 ? token : token.substring(0, PREFIX.length() + 4) + "…";
    }
}
