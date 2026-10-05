package com.younglings.bot.announcement;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns plain Discord-style text — the kind written with {@code **bold**} lines and {@code •} bullets —
 * into the heading-and-list formatting an embed post renders best, so pasted text doesn't need hand
 * editing first:
 * <ul>
 *   <li>a line that is only a bold phrase becomes a heading: {@code ##} for the very first line of the
 *       text (the title), {@code ###} for every later one;</li>
 *   <li>{@code •} and {@code ·} bullets become real {@code -} list items;</li>
 *   <li>everything else — bold lead-ins like {@code **The idea:** some text}, numbered lists, links,
 *       tags like {@code ~<LS>~}, code blocks — is left exactly as written.</li>
 * </ul>
 * Safe to run twice: its own output isn't touched again.
 */
public final class PostTextConverter {
    private PostTextConverter() {}

    // The whole line is one bold span (optionally with a trailing colon) and nothing else.
    private static final Pattern BOLD_ONLY = Pattern.compile("^\\s*\\*\\*([^*\\n]+?)\\*\\*\\s*:?\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[•·]\\s+(.*)$");

    public static String convert(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean seenContent = false;
        boolean inCode = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();

            if (trimmed.startsWith("```")) {
                inCode = !inCode;
                out.append(line);
            } else if (inCode) {
                out.append(line);
            } else {
                Matcher bold = BOLD_ONLY.matcher(line);
                Matcher bullet = BULLET.matcher(line);
                if (bold.matches()) {
                    out.append(seenContent ? "### " : "## ").append(bold.group(1).strip().replaceAll(":$", ""));
                } else if (bullet.matches()) {
                    out.append(bullet.group(1)).append("- ").append(bullet.group(2));
                } else {
                    out.append(line.stripTrailing());
                }
            }

            if (!trimmed.isEmpty()) seenContent = true;
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString().strip();
    }
}
