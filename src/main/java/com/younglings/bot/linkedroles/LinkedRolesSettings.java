package com.younglings.bot.linkedroles;

import java.net.URI;
import java.util.Base64;
import java.util.function.Function;

/**
 * The settings of Discord Linked Roles (T-LR), read from {@code LINKED_ROLES_*}:
 * <ul>
 *   <li>{@code LINKED_ROLES_ENABLED} {@code true} switches it on; off by default (optional features ship off).</li>
 *   <li>{@code LINKED_ROLES_TOKEN_KEY}: 32+ random bytes, base64, that seal the stored OAuth tokens (AES-256-GCM).</li>
 *   <li>{@code LINKED_ROLES_CLIENT_ID} / {@code LINKED_ROLES_CLIENT_SECRET}: the bot's Discord application (the same values as the website's
 *       {@code DISCORD_CLIENT_ID} / {@code DISCORD_CLIENT_SECRET}), to refresh a person's access token when it runs out.</li>
 *   <li>{@code LINKED_ROLES_DISCORD_API}: Discord's API base, {@code https://discord.com/api/v10} by default. Only for tests against a local
 *       mock: anything other than https is refused unless it is localhost.</li>
 *   <li>{@code LINKED_ROLES_REGISTER_ON_START}: {@code false} skips registering the metadata schema when the bot starts (on by default).</li>
 *   <li>{@code LINKED_ROLES_REQUIRE_CONFIRMED}: {@code true} counts an RS Link name only once the hub has confirmed the link from public data
 *       ({@code public_data_corroborated} or stronger, T-552); off by default. Names the bot verified itself still count. Metadata is per
 *       person, not per server (Discord's design), so this is one switch for the whole bot.</li>
 * </ul>
 * Switched on but missing the key or the application, it stays off and says what is missing, so a half-set-up deploy never stores a token
 * it can't seal or refresh.
 */
public record LinkedRolesSettings(boolean enabled, byte[] tokenKey, String clientId, String clientSecret, String apiBase, boolean registerOnStart, String problem,
                                  boolean requireConfirmed) {
    public static final String DEFAULT_API = "https://discord.com/api/v10";

    public static LinkedRolesSettings from(Function<String, String> env) {
        boolean wanted = "true".equalsIgnoreCase(trim(env.apply("LINKED_ROLES_ENABLED")));
        byte[] key = key(trim(env.apply("LINKED_ROLES_TOKEN_KEY")));
        String clientId = trim(env.apply("LINKED_ROLES_CLIENT_ID"));
        String secret = trim(env.apply("LINKED_ROLES_CLIENT_SECRET"));
        String api = apiBase(trim(env.apply("LINKED_ROLES_DISCORD_API")));
        boolean register = !"false".equalsIgnoreCase(trim(env.apply("LINKED_ROLES_REGISTER_ON_START")));
        boolean confirmedOnly = "true".equalsIgnoreCase(trim(env.apply("LINKED_ROLES_REQUIRE_CONFIRMED")));
        String problem = null;
        if (!wanted) problem = "LINKED_ROLES_ENABLED is not true";
        else if (key == null) problem = "LINKED_ROLES_TOKEN_KEY is missing or shorter than 32 bytes (base64)";
        else if (clientId == null || !clientId.matches("\\d{17,20}") || secret == null) problem = "LINKED_ROLES_CLIENT_ID / LINKED_ROLES_CLIENT_SECRET are missing";
        else if (api == null) problem = "LINKED_ROLES_DISCORD_API must be https (or http on localhost)";
        return new LinkedRolesSettings(problem == null, key, clientId, secret, api == null ? DEFAULT_API : api, register, problem, confirmedOnly);
    }

    /** The base without trailing slashes, or null when it may not be used. */
    static String apiBase(String raw) {
        if (raw == null) return DEFAULT_API;
        String url = raw.replaceAll("/+$", "");
        try {
            URI uri = URI.create(url);
            if (uri.getUserInfo() != null || uri.getHost() == null) return null;
            if ("https".equals(uri.getScheme())) return url;
            String host = uri.getHost();
            if ("http".equals(uri.getScheme()) && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"))) return url;
        } catch (IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    static byte[] key(String raw) {
        if (raw == null) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(raw.replace('-', '+').replace('_', '/'));
            return bytes.length >= 32 ? bytes : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String trim(String raw) {
        return raw == null || raw.isBlank() ? null : raw.strip();
    }
}
