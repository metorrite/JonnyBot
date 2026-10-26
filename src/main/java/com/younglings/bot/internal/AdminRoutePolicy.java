package com.younglings.bot.internal;

import java.util.Set;

/**
 * Which dashboard routes are slow enough (they ask Discord, or read a lot) to be served from {@link AdminReads}' cache, or to be given a time
 * limit. Everything else is untouched. The rules are only about the path and method, so they are easy to read and to test.
 */
final class AdminRoutePolicy {
    enum Mode { CACHED, BOUNDED, PLAIN }

    /** Pages whose answer may come from the cache (a GET): the dashboard shows them again and again and they rarely change. */
    private static final Set<String> CACHED_GETS = Set.of("structure", "points", "chat-levels", "citadel", "branding", "tempvoice", "appeals", "autopurge");
    /** Features whose writes talk to Discord and so may not finish quickly. */
    private static final Set<String> BOUNDED_WRITES = Set.of("branding", "tempvoice", "appeals");

    private AdminRoutePolicy() {}

    static Mode modeOf(String method, String[] parts) {
        if (parts.length == 0) return Mode.PLAIN;
        if (method.equals("GET")) {
            if (CACHED_GETS.contains(parts[0])) return Mode.CACHED;
            if (parts.length >= 3 && parts[0].equals("events") && parts[2].equals("space")) return Mode.CACHED;
            return Mode.PLAIN;
        }
        if (BOUNDED_WRITES.contains(parts[0])) return Mode.BOUNDED;
        if (parts.length == 2 && parts[1].equals("test") && (parts[0].equals("welcome") || parts[0].equals("ban-announcement"))) return Mode.BOUNDED;
        if (parts.length == 2 && parts[0].equals("autopurge") && parts[1].equals("preview")) return Mode.BOUNDED;
        return Mode.PLAIN;
    }
}
