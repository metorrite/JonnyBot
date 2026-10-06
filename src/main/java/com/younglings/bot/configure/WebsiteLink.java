package com.younglings.bot.configure;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * Turns what an admin typed into the clan website's address into something safe to put in a Discord link, or says it can't.
 * A missing scheme is assumed to be https; only http and https are accepted (never {@code javascript:} and the like), the host
 * must look like a real domain, and the characters that would break out of a markdown link are escaped.
 */
public final class WebsiteLink {
    public static final int MAX_LENGTH = 200;

    private WebsiteLink() {}

    /** The cleaned-up address, or empty if the text isn't a usable web address. Blank input is the caller's "clear it" case, not handled here. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) return Optional.empty();
        String text = raw.strip();
        if (text.isEmpty() || text.length() > MAX_LENGTH || text.chars().anyMatch(Character::isWhitespace)) return Optional.empty();
        if (!text.contains("://")) text = "https://" + text;

        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return Optional.empty();
        if (host == null || !host.contains(".") || host.startsWith(".") || host.endsWith(".")) return Optional.empty();

        // Parentheses and angle brackets would end the markdown link early, so they are sent as escapes.
        String safe = text.replace("(", "%28").replace(")", "%29").replace("<", "%3C").replace(">", "%3E");
        return safe.length() > MAX_LENGTH ? Optional.empty() : Optional.of(safe);
    }

    /** {@code [Younglings](https://…)} when there is a website, otherwise just the name. */
    public static String linkedTitle(String name, String websiteUrl) {
        return websiteUrl == null ? name : "[" + name + "](" + websiteUrl + ")";
    }
}
