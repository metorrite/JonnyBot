package com.runeclans.bot.web;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The rules for a signed-in person's small website settings (T-407, runeclans docs/BUILDER-V3.md 15.9 and 9.3): tour progress, the last
 * editor mode, their theme on a clan site. Pure, so the limits can be tested on their own. A value lives under a scope ({@code global}, or
 * {@code site:<server id>} for one clan site) and a key, and is any JSON value up to {@link #MAX_VALUE_BYTES}.
 */
public final class WebUserState {
    private WebUserState() {}

    public static final String GLOBAL = "global";
    public static final int MAX_KEYS_PER_SCOPE = 50;
    public static final int MAX_VALUE_BYTES = 2048;
    public static final int MAX_KEYS_PER_READ = 50;

    private static final Pattern SCOPE = Pattern.compile("global|site:[0-9]{15,20}");
    private static final Pattern KEY = Pattern.compile("[a-z0-9.-]{1,64}");
    private static final Pattern USER = Pattern.compile("[0-9]{15,20}");

    public static boolean scope(String scope) {
        return scope != null && SCOPE.matcher(scope).matches();
    }

    public static boolean key(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** A Discord user id as the website sends it; 0 when it isn't one. */
    public static long userId(String raw) {
        if (raw == null || !USER.matcher(raw).matches()) return 0;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Whether a value, as JSON text, is small enough to keep. */
    public static boolean fits(String json) {
        return json != null && json.getBytes(StandardCharsets.UTF_8).length <= MAX_VALUE_BYTES;
    }
}
