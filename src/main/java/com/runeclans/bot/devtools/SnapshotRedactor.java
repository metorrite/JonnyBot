package com.runeclans.bot.devtools;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Scrubs secrets out of every piece of text that goes into a dev snapshot. Three layers, because a leak is the one thing this
 * feature must never do:
 * <ol>
 *   <li><b>Known values</b>: every secret the bot was started with (tokens, passwords, the internal API secret, the password inside
 *       {@code DATABASE_URL}) is replaced wherever it appears, whatever surrounds it.</li>
 *   <li><b>Shapes</b>: things that look like a secret even if the bot was never told it (a Discord bot token, a connection string with
 *       a password, a {@code Bearer} header, {@code key=value} pairs with a secret-looking key, JWTs).</li>
 *   <li><b>Column names</b> ({@link #isSecretName}): a database column called token, secret, password, hash or key never has its value
 *       copied at all.</li>
 * </ol>
 */
public final class SnapshotRedactor {
    public static final String MASK = "[REDACTED]";

    /** Values shorter than this are not treated as known secrets: replacing "abc" everywhere would wreck the snapshot. */
    private static final int MIN_SECRET_LENGTH = 6;

    private static final Pattern SECRET_NAME = Pattern.compile(
            "(token|secret|passw(or)?d|passwd|hash|api[_-]?key|private[_-]?key|credential|authorization|cookie|session|salt|signature|^key$|_key$)");

    private record Shape(Pattern pattern, String replacement) {
        static Shape of(String regex, String replacement) {
            return new Shape(Pattern.compile(regex), replacement);
        }
    }

    private static final List<Shape> SHAPES = List.of(
            // Discord bot token: base64 id . timestamp . hmac
            Shape.of("[A-Za-z0-9_-]{23,28}\\.[A-Za-z0-9_-]{6,7}\\.[A-Za-z0-9_-]{27,}", MASK),
            // JWT
            Shape.of("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}", MASK),
            // scheme://user:password@host
            Shape.of("(?i)([a-z][a-z0-9+.-]*://[^\\s:/@]*:)[^\\s@/]+@", "$1" + MASK + "@"),
            // Authorization header values
            Shape.of("(?i)\\b(bearer|bot|basic)\\s+[A-Za-z0-9._~+/=-]{8,}", "$1 " + MASK),
            // "secret": "value", token=value, password: value, X-Internal-Secret: value ...
            Shape.of("(?i)([\"']?[A-Za-z0-9_.-]*(?:token|secret|passw(?:or)?d|passwd|api[_-]?key|private[_-]?key|credential|authorization)"
                    + "[A-Za-z0-9_.-]*[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',;&}]+", "$1" + MASK)
    );

    private final List<String> known;

    public SnapshotRedactor(Collection<String> secretValues) {
        this.known = secretValues.stream()
                .filter(v -> v != null && v.strip().length() >= MIN_SECRET_LENGTH)
                .map(String::strip)
                .distinct()
                // Longest first, so a secret that contains another is replaced whole.
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    /** True when a field or column of this name holds something that must not be copied. */
    public static boolean isSecretName(String name) {
        return name != null && SECRET_NAME.matcher(name.toLowerCase(Locale.ROOT)).find();
    }

    /** The text with every secret masked. */
    public String scrub(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (String secret : known) {
            out = out.replace(secret, MASK);
            // The same secret as it travels in URLs.
            String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
            if (!encoded.equals(secret)) out = out.replace(encoded, MASK);
        }
        for (Shape shape : SHAPES) out = shape.pattern().matcher(out).replaceAll(shape.replacement());
        return out;
    }
}
