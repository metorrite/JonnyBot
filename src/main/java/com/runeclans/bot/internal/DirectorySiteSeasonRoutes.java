package com.runeclans.bot.internal;

import com.runeclans.bot.clansite.ClanSiteService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * {@code GET sites/{slug}/data/season[?rsn=Name]}: the clan site's season standings widget and member badges (clan seasons, T-596), only
 * while the site is published. {@code {enabled: false}} unless both the bot's {@code CLAN_SEASONS} switch and the site's own are on; see
 * {@link SiteSeasons#publicAnswer}. Nothing here needs Discord, so a bot-less site gets it too.
 */
@BService
public class DirectorySiteSeasonRoutes implements DirectoryRouteModule {
    private static final java.util.regex.Pattern RSN = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _-]{0,11}");

    private final ClanSiteService sites;
    private final SiteSeasons seasons;
    private final RateLimiter perSite = new RateLimiter(20, 50);

    public DirectorySiteSeasonRoutes(ClanSiteService sites, SiteSeasons seasons) {
        this.sites = sites;
        this.seasons = seasons;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        String[] parts = request.parts();
        if (!request.head().equals("sites") || parts.length != 4 || !parts[2].equals("data") || !parts[3].equals("season")) return false;
        if (!request.requireGet()) return true;
        Optional<Long> guildId = request.viewable(parts[1]).map(p -> p.guildId()).filter(id -> sites.visible(id).isPresent());
        if (guildId.isEmpty()) {
            request.sendError(404, "Not found");
            return true;
        }
        if (!perSite.tryAcquire(parts[1])) {
            request.sendError(429, "Too many requests");
            return true;
        }
        String rsn = request.query("rsn");
        if (rsn != null && !RSN.matcher(rsn.strip()).matches()) rsn = null;
        request.send(200, seasons.publicAnswer(guildId.get(), rsn, OffsetDateTime.now(ZoneOffset.UTC)));
        return true;
    }
}
