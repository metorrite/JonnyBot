package com.younglings.bot.discord;

/**
 * Parses the "paste a link or just the ID" text field that backs every "Link by ID" fallback button
 * (see {@link Containers#linkButton}) — the one escape hatch for a channel, thread, or role that a
 * native Discord select menu won't surface (a forum post in particular; Discord's channel-select
 * component can't list individual threads at all) or that the picker's underlying permission checks
 * hide even though the bot can otherwise see and use it.
 */
public final class DiscordLinks {
    private DiscordLinks() {}

    /** A pasted message/channel link ({@code .../channels/<guild>/<channel>[/<message>]}) or a bare numeric ID; {@code null} if neither parses. */
    public static Long parseChannelId(String input) {
        String trimmed = input.trim();
        if (trimmed.contains("/channels/")) {
            String[] afterMarker = trimmed.split("/channels/", 2)[1].split("/");
            if (afterMarker.length < 2) return null;
            try {
                return Long.parseLong(afterMarker[1]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return parseId(trimmed);
    }

    /** A bare numeric ID (Discord has no user-facing link format for a role) — {@code null} if it doesn't parse. */
    public static Long parseId(String input) {
        try {
            return Long.parseLong(input.trim().replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
