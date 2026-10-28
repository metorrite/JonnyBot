package com.runeclans.bot.util;

import java.util.Random;

/**
 * Fake secrets for tests, built at runtime so no secret-looking literal is ever committed (GitGuardian and gitleaks
 * flag literals, even fake ones). Use these for redaction/scrubbing tests instead of pasting a token-shaped string.
 * The values are random each run, never match a real credential, and carry no meaning outside the test.
 */
public final class TestSecrets {
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Random RANDOM = new Random();

    private TestSecrets() {
    }

    /** A random secret-shaped value of the given length (letters and digits), labelled so a failing test is readable. */
    public static String fake(String label) {
        return fake(label, 32);
    }

    public static String fake(String label, int length) {
        return label + "-" + random(length);
    }

    /** Shaped like a Discord bot token: three dot-separated parts, assembled here rather than written out. */
    public static String fakeDiscordToken() {
        return random(24) + "." + random(6) + "." + random(27);
    }

    /** Shaped like a Postgres URL with a password, assembled at runtime. */
    public static String fakePostgresUrl() {
        return "postgres" + "ql://" + "user" + ":" + random(16) + "@" + "127.0.0.1:5432/testdb";
    }

    private static String random(int length) {
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }
}
