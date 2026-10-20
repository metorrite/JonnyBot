package com.younglings.bot.rsnlink;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * The shapes of everything the service hands out or accepts (LINK-SYSTEM.md section 5.1):
 * <ul>
 *   <li>user codes: 8 symbols from {@value #ALPHABET} (no vowels, so no accidental words; no 0/O, 1/I/L look-alikes), shown {@code XXXX-XXXX};</li>
 *   <li>device secrets: 32 random bytes, base64url;</li>
 *   <li>link tokens: {@value #TOKEN_PREFIX} + 43 base64url characters, a different prefix from the clan plugin's {@code jbp_} tokens;</li>
 *   <li>RSNs, cleaned the same way as the clan plugin's ingest, plus the lookup form used to match names;</li>
 *   <li>plugin names for aliases.</li>
 * </ul>
 */
public final class LinkCodes {
    public static final String ALPHABET = "BCDFGHJKMNPQRSTVWXZ23456789";
    public static final int CODE_LENGTH = 8;
    public static final String TOKEN_PREFIX = "lnk_";

    private LinkCodes() {}

    /** A new user code, already in its display form {@code XXXX-XXXX}. */
    public static String newUserCode(SecureRandom random) {
        StringBuilder code = new StringBuilder(CODE_LENGTH + 1);
        for (int i = 0; i < CODE_LENGTH; i++) {
            if (i == 4) code.append('-');
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * What a player typed, made comparable: upper case, spaces and hyphens dropped. Null when it can't be a code at all (wrong length or
     * a symbol that is never used), so such input is refused before anything is looked up.
     */
    public static String normaliseCode(String typed) {
        if (typed == null) return null;
        String code = typed.toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "");
        if (code.length() != CODE_LENGTH) return null;
        for (int i = 0; i < code.length(); i++) if (ALPHABET.indexOf(code.charAt(i)) < 0) return null;
        return code;
    }

    public static String newDeviceSecret(SecureRandom random) {
        return random32(random);
    }

    public static String newToken(SecureRandom random) {
        return TOKEN_PREFIX + random32(random);
    }

    public static boolean looksLikeToken(String text) {
        return text != null && text.length() == TOKEN_PREFIX.length() + 43 && text.startsWith(TOKEN_PREFIX)
                && text.substring(TOKEN_PREFIX.length()).matches("[A-Za-z0-9_-]{43}");
    }

    public static boolean looksLikeDeviceSecret(String text) {
        return text != null && text.matches("[A-Za-z0-9_-]{43}");
    }

    /** A display name as the game shows it: non-breaking spaces made ordinary, 1 to 12 of letters, digits, space, - and _. Null if not. */
    public static String normaliseRsn(String raw) {
        if (raw == null) return null;
        String name = raw.replace(' ', ' ').strip().replaceAll("\\s+", " ");
        return name.matches("[A-Za-z0-9 _-]{1,12}") ? name : null;
    }

    /** The form names are matched in: lower case, with space, _ and - treated alike (the game treats them alike too). */
    public static String rsnLookup(String rsn) {
        return rsn.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
    }

    /** A tracking id as the plugin sends it (a 64-bit value as a decimal string, possibly negative), or null when absent or malformed. */
    public static String normaliseTrackingId(String raw) {
        if (raw == null) return null;
        String id = raw.strip();
        return id.matches("-?\\d{1,20}") ? id : null;
    }

    /** A plugin name for aliases: trimmed, 1 to 64 of letters, digits, '.', '_', '-', compared in lower case. Null if not. */
    public static String normalisePluginName(String raw) {
        if (raw == null) return null;
        String name = raw.strip();
        return name.matches("[A-Za-z0-9._ -]{1,64}") && !name.isBlank() ? name.toLowerCase(Locale.ROOT) : null;
    }

    private static String random32(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
