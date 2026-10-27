package com.younglings.bot.ops;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The request id that follows one request from the website through the bot to the hub ({@code X-Request-Id}, T-674; the same rules as the hub and the website).
 * An incoming id is kept only when it looks like one (8 to 128 letters, digits, {@code . _ : -}), so a caller can't put log-breaking text
 * into the logs; otherwise a fresh one is made.
 */
public final class RequestIds {
    public static final String HEADER = "X-Request-Id";
    /** The logging context key ({@code org.slf4j.MDC}) the id is kept under while a request runs. */
    public static final String MDC_KEY = "requestId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private RequestIds() {}

    /** The incoming id when it is well formed, else null. */
    public static String accept(String incoming) {
        if (incoming == null) return null;
        String id = incoming.strip();
        return VALID.matcher(id).matches() ? id : null;
    }

    /** The incoming id when it is well formed, else a new one. */
    public static String orNew(String incoming) {
        String id = accept(incoming);
        return id != null ? id : newId();
    }

    /** 16 random bytes as 32 hex characters. */
    public static String newId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
