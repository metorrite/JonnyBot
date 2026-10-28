package com.runeclans.bot.configure;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Discord server invite an admin typed, cleaned up for showing to players: only {@code discord.gg/<code>} and {@code discord.com/invite/<code>} links are
 * accepted (a missing scheme is assumed to be https), and the result is always the short {@code https://discord.gg/<code>} form.
 */
public final class InviteLink {
    private static final Pattern INVITE = Pattern.compile("^(?:https?://)?(?:www\\.)?(?:discord\\.gg|discord(?:app)?\\.com/invite)/([A-Za-z0-9-]{2,32})/?$", Pattern.CASE_INSENSITIVE);

    private InviteLink() {}

    /** The canonical invite address, or empty if the text isn't a Discord invite. Blank input is the caller's "clear it" case, not handled here. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) return Optional.empty();
        Matcher m = INVITE.matcher(raw.strip());
        return m.matches() ? Optional.of("https://discord.gg/" + m.group(1)) : Optional.empty();
    }
}
