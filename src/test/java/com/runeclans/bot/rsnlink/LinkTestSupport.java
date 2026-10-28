package com.runeclans.bot.rsnlink;

import com.runeclans.bot.config.EnvAliases;
import org.junit.jupiter.api.Assumptions;

import java.net.URI;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Test helpers for the link service. Keys are drawn at random for every run, so no key-shaped value is ever written in the repository.
 * The database is a local or CI Postgres only: {@code RUNECLAN_BOT_TEST_LINK_DATABASE_URL} (old name {@code JONNYBOT_TEST_LINK_DATABASE_URL}), else CI's {@code STARTUP_CHECK_DATABASE_URL};
 * a test is skipped when neither is set. Tests use the schema {@code rsnlink_test}, dropped and made fresh each time, never {@code rsnlink}.
 */
final class LinkTestSupport {
    static final String TEST_SCHEMA = "rsnlink_test";

    private LinkTestSupport() {}

    static String randomKey(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** A complete environment with fresh random keys and the given database address. */
    static Map<String, String> env(String databaseUrl) {
        SecureRandom random = new SecureRandom();
        Map<String, String> env = new HashMap<>();
        env.put("LINK_DATABASE_URL", databaseUrl);
        env.put("LINK_ENC_KEY", randomKey(random));
        env.put("LINK_LOOKUP_KEY", randomKey(random));
        env.put("LINK_ALIAS_KEY", randomKey(random));
        env.put("LINK_SIGNING_KEY", randomKey(random));
        env.put("LINK_PUBLIC_BASE_URL", "http://localhost:3000");
        return env;
    }

    static LinkSettings settings(String databaseUrl) {
        return LinkSettings.fromEnv(env(databaseUrl)::get).withSchema(TEST_SCHEMA);
    }

    /** The test database address, or the calling test is skipped. */
    static String databaseUrl() {
        String url = EnvAliases.get("RUNECLAN_BOT_TEST_LINK_DATABASE_URL", "JONNYBOT_TEST_LINK_DATABASE_URL");
        if (url == null || url.isBlank()) url = System.getenv("STARTUP_CHECK_DATABASE_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "no link test database (set RUNECLAN_BOT_TEST_LINK_DATABASE_URL)");
        String host = URI.create(url.strip()).getHost();
        Assumptions.assumeTrue("127.0.0.1".equals(host) || "localhost".equals(host), "refusing a database that isn't local");
        return url.strip();
    }

    static void resetSchema(LinkDatabase database) throws Exception {
        try (Connection c = database.connection(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
        }
    }

    /** A clock tests can move. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
