package com.younglings.bot.internal;

import com.younglings.bot.clansite.ClanSite;
import com.younglings.bot.clansite.ClanSiteService;
import com.younglings.bot.discord.Shards;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.io.IOException;
import java.util.Optional;

/**
 * "In game now" on a clan website (T-586): {@code GET sites/{slug}/data/in-game} answers who in the clan is in the game with RuneSync now
 * ({@link SiteInGameNow}), only while the site is published. RuneScape names only, and only of members who switched on RuneSync's Presence
 * category; nobody at all until the published layout holds an "In game now" widget. The answer says who the site lets see the list
 * ({@code visibility}: {@code members} by default, or {@code public}); the website shows it only to them. Works for a site without the bot.
 */
@BService
public class DirectoryInGameRoutes implements DirectoryRouteModule {
    private final ClanSiteService sites;
    private final SiteInGameNow inGame;

    public DirectoryInGameRoutes(ClanSiteService sites, SiteInGameNow inGame) {
        this.sites = sites;
        this.inGame = inGame;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        String[] parts = request.parts();
        if (!request.head().equals("sites") || parts.length != 4 || !parts[2].equals("data") || !parts[3].equals("in-game")) return false;
        if (!request.requireGet()) return true;
        Optional<ClanSite> site = request.viewable(parts[1]).flatMap(p -> sites.visible(p.guildId()));
        if (site.isEmpty()) {
            request.sendError(404, "Not found");
            return true;
        }
        long guildId = site.get().guildId();
        request.send(200, inGame.answer(guildId, Shards.guild(request.jda(), guildId)));
        return true;
    }
}
