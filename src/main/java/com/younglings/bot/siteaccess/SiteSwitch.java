package com.younglings.bot.siteaccess;

import java.util.Arrays;
import java.util.Optional;

/** The website's gates the owner can open or close without a redeploy. The key is what the website and the dashboard use. */
public enum SiteSwitch {
    JONNYBOT("jonnybot", "JonnyBot website", "Home, docs, install, FAQ, privacy and terms. While closed only trusted admins see them."),
    HUB("hub", "Clans hub", "The public directory of clans, events and leaderboards. While closed only trusted admins see it."),
    DASHBOARD("dashboard", "Bot dashboard", "The per-server bot dashboard. While closed only trusted admins can sign in to it.");

    private final String key;
    private final String label;
    private final String description;

    SiteSwitch(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description;
    }

    public String key() { return key; }
    public String label() { return label; }
    public String description() { return description; }

    public static Optional<SiteSwitch> ofKey(String key) {
        return Arrays.stream(values()).filter(s -> s.key.equals(key)).findFirst();
    }
}
