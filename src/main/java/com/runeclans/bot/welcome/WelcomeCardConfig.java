package com.runeclans.bot.welcome;

/**
 * A server's optional welcome picture: a 700x300 card with a background (colour, or a picture), the new member's avatar and two lines of
 * text. It is attached to the welcome message when {@code enabled} (classic messages only: a Container can't carry an attachment).
 * The texts may use {@code {name}} (display name), {@code {username}}, {@code {server}}, {@code {count}} and {@code {date}}.
 */
public record WelcomeCardConfig(long guildId, boolean enabled, int backgroundColor, String backgroundImageUrl, int textColor, String title, String description) {
    public static final int MAX_TITLE = 60;
    public static final int MAX_DESCRIPTION = 120;
    public static final int MAX_URL = 2000;

    public static WelcomeCardConfig defaults(long guildId) {
        return new WelcomeCardConfig(guildId, false, 0x1E2230, "", 0xFFFFFF, "Welcome, {name}!", "You are member #{count} of {server}");
    }

    /** Fills the card's variables for one member. */
    public static String fill(String template, String name, String username, String server, int count, String date) {
        return template.replace("{name}", name).replace("{username}", username).replace("{server}", server)
                .replace("{count}", String.valueOf(count)).replace("{date}", date);
    }
}
