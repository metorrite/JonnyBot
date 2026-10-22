package com.younglings.bot.runeclans;

import java.util.function.Function;

/**
 * Whether JonnyBot reads and writes plugin data through the RuneClans hub (runeclans-hub docs/HUB-DESIGN.md section 7). Off unless
 * {@code RUNECLANS_HUB_ENABLED=true} and both {@code RUNECLANS_HUB_URL} and {@code RUNECLANS_HUB_API_KEY} are set; off, the bot's own RS Link
 * and plugin ingest code does everything exactly as before. The key is never logged ({@link #toString()} hides it).
 *
 * @param enabled whether the hub is used
 * @param baseUrl the hub's address without a trailing slash, e.g. {@code http://localhost:8090}
 * @param apiKey  JonnyBot's first-party API key ({@code rsh_...}) with {@code link:resolve}, {@code players:read}, {@code feed:read},
 *                {@code guilds:write}
 * @param problem why the hub is off although {@code RUNECLANS_HUB_ENABLED} is true (names variables, never values), or null
 */
public record RuneClansSettings(boolean enabled, String baseUrl, String apiKey, String problem) {
    public static final RuneClansSettings OFF = new RuneClansSettings(false, null, null, null);

    public static RuneClansSettings from(Function<String, String> env) {
        if (!"true".equalsIgnoreCase(trimmed(env.apply("RUNECLANS_HUB_ENABLED")))) return OFF;
        String url = trimmed(env.apply("RUNECLANS_HUB_URL"));
        String key = trimmed(env.apply("RUNECLANS_HUB_API_KEY"));
        StringBuilder problems = new StringBuilder();
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1"))) {
            problems.append(" RUNECLANS_HUB_URL must be an https address (plain http only for localhost).");
        }
        if (key == null || !key.matches("rsh_[A-Za-z0-9_-]{43}")) problems.append(" RUNECLANS_HUB_API_KEY must be a hub API key (rsh_...).");
        if (!problems.isEmpty()) return new RuneClansSettings(false, null, null, "RUNECLANS_HUB_ENABLED is true but:" + problems);
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return new RuneClansSettings(true, url, key, null);
    }

    private static String trimmed(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    @Override
    public String toString() {
        return "RuneClansSettings[enabled=" + enabled + ", baseUrl=" + baseUrl + ", key=hidden]";
    }
}
