package com.younglings.bot.access;

import java.util.Arrays;
import java.util.Optional;
import com.younglings.bot.config.BotBrand;

/**
 * The restricted areas of the website that the owner can let people into by hand (see {@link AccessGrantService}). Adding a new restricted
 * area is one line here; the website names the same key when it asks whether someone may enter.
 */
public enum AccessArea {
    JONNYBOT("jonnybot", BotBrand.NAME + " website"),
    HUB("hub", "Clans hub"),
    DASHBOARD("dashboard", "Bot dashboard"),
    /** Running a clan website for a server the bot isn't in (a bot-less site); only a grant lets someone in, never being a beta server's admin. */
    SITE_BUILDER_BETA("site-builder-beta", "Site builder without the bot (beta)");

    private final String key;
    private final String label;

    AccessArea(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() { return key; }
    public String label() { return label; }

    public static Optional<AccessArea> ofKey(String key) {
        return Arrays.stream(values()).filter(a -> a.key.equals(key)).findFirst();
    }
}
