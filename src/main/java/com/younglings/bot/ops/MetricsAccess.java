package com.younglings.bot.ops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.Function;

/**
 * Who may read {@code /metrics}: nobody unless {@code METRICS_ENABLED} is on and {@code METRICS_TOKEN} (24+ characters) is set; then only a
 * caller that sends {@code Authorization: Bearer <METRICS_TOKEN>} and came over the private network. A request that passed a public edge
 * (Railway's or Cloudflare's proxy adds {@code X-Forwarded-For}) is refused even with the token. Every refusal looks like a missing page.
 */
public final class MetricsAccess {
    public static final int MIN_TOKEN_LENGTH = 24;
    private static final String[] PROXY_HEADERS = {"X-Forwarded-For", "X-Real-Ip", "Forwarded", "CF-Connecting-IP"};

    private MetricsAccess() {}

    /** Whether the setting turns something on: {@code on}, {@code true}, {@code 1} or {@code yes}, any case. */
    public static boolean on(String raw) {
        if (raw == null) return false;
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "on", "true", "1", "yes" -> true;
            default -> false;
        };
    }

    /** The token when it is long enough to use, else null (and metrics stay off). */
    public static String token(String raw) {
        if (raw == null) return null;
        String t = raw.strip();
        return t.length() >= MIN_TOKEN_LENGTH ? t : null;
    }

    /** {@code header} looks a request header up by name (case-insensitively). */
    public static boolean allowed(boolean enabled, String token, Function<String, String> header) {
        if (!enabled || token == null) return false;
        for (String h : PROXY_HEADERS) if (header.apply(h) != null) return false;
        String auth = header.apply("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return false;
        byte[] given = auth.substring("Bearer ".length()).strip().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, token.getBytes(StandardCharsets.UTF_8));
    }
}
