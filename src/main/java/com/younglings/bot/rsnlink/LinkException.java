package com.younglings.bot.rsnlink;

/**
 * A refusal the HTTP layer turns into {@code { "error": code, "message": ..., "retryAfter": ... }} (LINK-SYSTEM.md section 5). Messages
 * are plain words for players and never contain a code, token, Discord id, tracking id or IP.
 */
public class LinkException extends RuntimeException {
    private final int status;
    private final String code;
    private final Integer retryAfter;
    private final transient java.util.Map<String, Object> extra;

    public LinkException(int status, String code, String message) {
        this(status, code, message, null, null);
    }

    public LinkException(int status, String code, String message, Integer retryAfter, java.util.Map<String, Object> extra) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
        this.extra = extra;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Integer retryAfter() {
        return retryAfter;
    }

    public java.util.Map<String, Object> extra() {
        return extra;
    }

    /** Rate limits and blocks look exactly the same, so a blocked party learns nothing extra (section 8). */
    public static LinkException slowDown(int retryAfterSeconds) {
        return new LinkException(429, "rate_limited", "Too many tries. Wait a little and try again.", retryAfterSeconds, null);
    }

    public static LinkException bad(String code, String message) {
        return new LinkException(400, code, message);
    }
}
