package com.younglings.bot.appeal;

import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

/**
 * Where a punished member appeals (T-033): the server's own appeal page on the website, {@code <SITE_URL>/jonnybot/appeal/<server>}, once the server
 * takes appeals there. That address works with or without the JonnyBot subdomain (the site moves /jonnybot pages to it by itself). Null when appeals are
 * off or the site's address isn't set; a server's own appeal link (moderation settings) always wins over this.
 */
@BService
public class AppealLinks {
    private final AppealRepository appeals;
    private final BotConfig config;

    public AppealLinks(AppealRepository appeals, BotConfig config) {
        this.appeals = appeals;
        this.config = config;
    }

    public String linkFor(long guildId) {
        try {
            if (!appeals.settings(guildId).enabled()) return null;
        } catch (RuntimeException e) {
            return null; // a moderation DM never fails over the appeal link
        }
        return linkFrom(config.getSiteUrl(), guildId);
    }

    static String linkFrom(String siteUrl, long guildId) {
        return siteUrl == null || siteUrl.isBlank() ? null : siteUrl + "/jonnybot/appeal/" + guildId;
    }
}
