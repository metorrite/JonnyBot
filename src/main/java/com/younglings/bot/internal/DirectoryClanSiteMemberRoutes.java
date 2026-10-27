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
 * A signed-in member's own actions on a clan website (T-369), for the server behind that site: {@code sites/{slug}/me/<route>} does what the
 * home site's {@code /internal/me/<route>} does ({@link MemberApi}: link, settings, goals, roles, polls and votes, signups, events, coffer,
 * highlights, level), plus {@code notices} (what the home site's {@code site/me} lists) and the server profile ({@code server-profile},
 * {@code nickname}, {@code color-role}; each change only while the server's admins allow it, {@link SiteMemberOptionsAdminApi}).
 * <p>
 * A bot-less site (the bot isn't in its server) answers 409 {@code needsBot}: nobody's membership can be checked, so nothing is read or written.
 * Only for a published site, and only for someone who is in that Discord server: anyone else is refused before anything is read or
 * written. Like every "me" route, the {@code userId} (query for GET/DELETE, body for POST/PUT) must come from the visitor's verified login
 * session on the website, never from the browser; every action touches only that user's rows in this one server.
 */
@BService
public class DirectoryClanSiteMemberRoutes implements DirectoryRouteModule {
    private final ClanSiteService sites;
    private final MemberApi memberApi;
    private final SiteApi siteApi;

    public DirectoryClanSiteMemberRoutes(ClanSiteService sites, MemberApi memberApi, SiteApi siteApi) {
        this.sites = sites;
        this.memberApi = memberApi;
        this.siteApi = siteApi;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        String[] parts = request.parts();
        if (!request.head().equals("sites") || parts.length < 4 || parts.length > 5 || !parts[2].equals("me")) return false;
        Optional<ClanSite> site = request.viewable(parts[1]).flatMap(p -> sites.visible(p.guildId()));
        Guild guild = site.isEmpty() ? null : Shards.guild(request.jda(), site.get().guildId());
        if (guild == null) {
            // a bot-less site's server has no bot to know its members, so a member's actions can't be done there: say so, don't 404
            if (site.isPresent() && sites.botless(site.get().guildId())) {
                request.send(409, DataObject.empty().put("error", BotlessAdminApi.NEEDS_BOT).put("needsBot", true).put("botless", true));
            } else {
                request.sendError(404, "Not found");
            }
            return true;
        }
        String route = parts.length == 4 ? parts[3] : parts[3] + "/" + parts[4];
        if (route.equals("notices")) {
            notices(request, guild);
            return true;
        }
        memberApi.handle(request.exchange(), guild, route, true);
        return true;
    }

    /** The member's link state and recent notices (a link request decided, goals reached), as the home site's {@code site/me}. */
    private void notices(DirectoryRequest request, Guild guild) throws IOException {
        if (!request.requireGet()) return;
        long userId;
        try {
            userId = Long.parseLong(request.query("userId") == null ? "" : request.query("userId").trim());
        } catch (NumberFormatException e) {
            request.sendError(400, "userId must be numeric");
            return;
        }
        if (ServerProfile.resolveMember(guild, userId) == null) {
            request.sendError(403, MemberApi.NOT_IN_SERVER);
            return;
        }
        DataObject me = siteApi.me(guild, Long.toString(userId));
        request.send(200, me);
    }
}
