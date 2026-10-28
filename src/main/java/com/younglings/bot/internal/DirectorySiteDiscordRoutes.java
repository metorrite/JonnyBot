package com.younglings.bot.internal;

import com.younglings.bot.discord.Shards;
import com.younglings.bot.clansite.ClanSite;
import com.younglings.bot.clansite.ClanSiteService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.util.Optional;

/**
 * Discord on a clan website (builder v3, T-365), only while the site is published and the server is in the beta:
 * <ul>
 *   <li>{@code GET sites/{slug}/data/discord/online}: who's online, as much as the site's mode allows ({@code count}, {@code names},
 *   {@code names+avatars}); 404 {@code disabled} while the mode is off or the server is disconnected; {@code unavailable} without the Presence intent</li>
 *   <li>{@code GET sites/{slug}/data/discord/feed?channel=}: the newest posts from the channels the admin opted in (all of them, or one),
 *   as many as the site's feed setting (N7, at most 25); 404 for a channel that isn't one of them</li>
 *   <li>{@code GET|POST sites/{slug}/discord/me?userId=}: a signed-in member's own choice to be left out of the online names
 *   ({@code {"hidden": true}}); the user id comes only from the website's verified session</li>
 * </ul>
 * A bot-less site (the bot isn't in its server) answers {@code {"needsBot": true}} to all of these, and to any other {@code data/discord/...} route.
 * Each site gets 10 requests a second (bursts of 20); answers are cached per server by {@link SiteDiscordService}.
 */
@BService
public class DirectorySiteDiscordRoutes implements DirectoryRouteModule {
    private final ClanSiteService sites;
    private final SiteDiscordService discord;
    private final RateLimiter perSite = new RateLimiter(20, 100);

    public DirectorySiteDiscordRoutes(ClanSiteService sites, SiteDiscordService discord) {
        this.sites = sites;
        this.discord = discord;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        String[] parts = request.parts();
        if (!request.head().equals("sites")) return false;
        boolean data = parts.length == 5 && parts[2].equals("data") && parts[3].equals("discord") && (parts[4].equals("online") || parts[4].equals("feed"));
        boolean me = parts.length == 4 && parts[2].equals("discord") && parts[3].equals("me");
        if (!data && !me) {
            // any other data/discord/... route: a bot-less site still says the bot is needed, the rest is left to the other modules
            boolean discordData = parts.length >= 5 && parts[2].equals("data") && parts[3].equals("discord");
            if (!discordData || guild(request, parts[1]) != null || !botless(request, parts[1])) return false;
            if (request.requireGet()) request.send(200, SiteApi.needsBot());
            return true;
        }

        if (!perSite.tryAcquire(parts[1])) {
            request.sendError(429, "Too many requests");
            return true;
        }
        Guild guild = guild(request, parts[1]);
        if (guild == null && botless(request, parts[1])) {
            // the bot isn't in a bot-less site's server, so nothing of Discord can be shown; the widget says why
            if (data ? request.requireGet() : true) request.send(200, SiteApi.needsBot());
            return true;
        }
        if (data) {
            if (!request.requireGet()) return true;
            if (guild == null) {
                request.sendError(404, "Not found");
                return true;
            }
            if (parts[4].equals("online")) {
                DataObject online = discord.online(guild);
                if (online == null) request.sendError(404, "disabled");
                else request.send(200, online);
            } else {
                DataObject feed = discord.feed(guild, request.query("channel"));
                if (feed == null) request.sendError(404, "Not found");
                else request.send(200, feed);
            }
            return true;
        }

        String rawUser = request.query("userId");
        if (rawUser == null || !rawUser.matches("\\d{17,20}")) {
            request.sendError(400, "userId is required");
            return true;
        }
        if (guild == null) {
            request.sendError(404, "Not found");
            return true;
        }
        long userId = Long.parseLong(rawUser);
        if (request.method().equals("GET")) {
            request.send(200, discord.memberChoice(guild, userId));
        } else if (request.requirePost()) {
            DataObject body = request.body("Unreadable body");
            if (body == null) return true;
            request.send(200, discord.saveMemberChoice(guild, userId, body.getBoolean("hidden", false)));
        }
        return true;
    }

    private Guild guild(DirectoryRequest request, String slug) {
        Optional<ClanSite> site = request.viewable(slug).flatMap(p -> sites.visible(p.guildId()));
        return site.isEmpty() ? null : Shards.guild(request.jda(), site.get().guildId());
    }

    /** Whether the site at {@code slug} is a live bot-less one. */
    private boolean botless(DirectoryRequest request, String slug) {
        return request.viewable(slug).flatMap(p -> sites.visible(p.guildId())).map(site -> sites.botless(site.guildId())).orElse(false);
    }
}
