package com.younglings.bot.testsupport;

import java.net.URI;

/**
 * The address of the scratch Postgres for tests that need real SQL, as a JDBC URL. {@code JONNYBOT_TEST_DATABASE_URL} (the {@code postgresql://user:pass@host:port/db} form
 * that CI and the cloud environment set) is used first; the older {@code JONNYBOT_TEST_DB_URL} (already a JDBC URL) is the fallback. Neither set means the tests skip.
 */
public final class TestDatabaseUrl {
    private TestDatabaseUrl() {}

    /** The JDBC URL, or null when no scratch database is configured. */
    public static String jdbcOrNull() {
        String uri = System.getenv("JONNYBOT_TEST_DATABASE_URL");
        if (uri != null && !uri.isBlank()) return toJdbc(uri.trim());
        String legacy = System.getenv("JONNYBOT_TEST_DB_URL");
        return legacy == null || legacy.isBlank() ? null : legacy.trim();
    }

    /** For {@code @EnabledIf}: true when a scratch database is configured. */
    public static boolean configured() {
        return jdbcOrNull() != null;
    }

    /** The JDBC URL; only call once {@link #configured()} is true. */
    public static String jdbc() {
        String url = jdbcOrNull();
        if (url == null) throw new IllegalStateException("no scratch database configured");
        return url;
    }

    static String toJdbc(String raw) {
        if (raw.startsWith("jdbc:")) return raw;
        URI uri = URI.create(raw);
        String base = "jdbc:postgresql://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getPath();
        if (uri.getUserInfo() == null) return base;
        String[] user = uri.getUserInfo().split(":", 2);
        return base + "?user=" + user[0] + (user.length > 1 && !user[1].isEmpty() ? "&password=" + user[1] : "");
    }
}
