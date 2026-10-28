package com.younglings.bot.internal;

import com.younglings.bot.discord.Shards;
import com.younglings.bot.clansite.ClanSite;
import com.younglings.bot.clansite.ClanSiteService;
import com.younglings.bot.twitch.TwitchClient.Stream;
import com.younglings.bot.twitch.TwitchRules;
import com.younglings.bot.twitch.TwitchService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code GET sites/{slug}/data/streams}: the clan's streamers who are live on Twitch now, for the clan website's "Live now" widget (T-587),
 * only while the site is published. They are the streamers the server follows for its Twitch go-live alerts, from the alerts' own poll (every
 * two minutes), so the website never asks Twitch itself. {@code {following, streams:[{login, name, title, game, viewers, url, thumbnail}]}},
 * most viewers first; {@code following} false while the server's Twitch alerts are off. A bot-less site answers {@code {"needsBot": true}}.
 */
@BService
public class DirectorySiteStreamRoutes implements DirectoryRouteModule {
    static final int MAX_STREAMS = 12;

    private final ClanSiteService sites;
    private final TwitchService twitch;

    public DirectorySiteStreamRoutes(ClanSiteService sites, TwitchService twitch) {
        this.sites = sites;
        this.twitch = twitch;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        String[] parts = request.parts();
        if (!request.head().equals("sites") || parts.length != 4 || !parts[2].equals("data") || !parts[3].equals("streams")) return false;
        if (!request.requireGet()) return true;
        Optional<ClanSite> site = request.viewable(parts[1]).flatMap(p -> sites.visible(p.guildId()));
        if (site.isEmpty()) {
            request.sendError(404, "Not found");
            return true;
        }
        long guildId = site.get().guildId();
        if (Shards.guild(request.jda(), guildId) == null) {
            if (sites.botless(guildId)) request.send(200, SiteApi.needsBot());
            else request.sendError(404, "Not found");
            return true;
        }
        request.send(200, json(twitch.followsStreamers(guildId), twitch.liveFor(guildId, Instant.now()), System.currentTimeMillis()));
        return true;
    }

    /** The answer; the thumbnail address changes every five minutes, so a cached page shows a fresh picture without asking Twitch more. */
    public static DataObject json(boolean following, java.util.List<Stream> live, long nowMillis) {
        DataArray streams = DataArray.empty();
        for (Stream s : live.subList(0, Math.min(live.size(), MAX_STREAMS))) {
            streams.add(DataObject.empty().put("login", s.login()).put("name", s.name().isBlank() ? s.login() : s.name()).put("title", s.title())
                    .put("game", s.game()).put("viewers", s.viewers()).put("url", "https://www.twitch.tv/" + s.login())
                    .put("thumbnail", TwitchRules.thumbnail(s.thumbnailTemplate(), 440, 248, nowMillis - nowMillis % 300_000)));
        }
        return DataObject.empty().put("following", following).put("streams", streams);
    }
}
