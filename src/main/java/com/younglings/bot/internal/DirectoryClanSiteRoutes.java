package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.clansite.ClanSite;
import com.younglings.bot.clansite.ClanSiteBlock;
import com.younglings.bot.clansite.ClanSiteLayoutRepository;
import com.younglings.bot.clansite.ClanSitePluginFeed;
import com.younglings.bot.clansite.ClanSiteService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.util.Optional;

/**
 * Clan websites from the builder (T-065, T-066), only while published and the server is in the beta:
 * <ul>
 *   <li>{@code GET sites/{slug}}: the site's look, pages and content blocks, and its published builder v2 layout ({@code layout}, null for a
 *   v1 site; T-301); or {@code {"movedTo": slug}} for an address the clan gave up less
 *   than {@link com.younglings.bot.clanprofile.ProfileRules#SLUG_GRACE} ago, so its old subdomain forwards (T-290)</li>
 *   <li>{@code GET sites/{slug}/data/plugin/{activity|drops|runesync}?limit=}: the clan's recent plugin events from the RuneClans hub, for the
 *   builder's activity widgets ({@link ClanSitePluginFeed}, T-303)</li>
 *   <li>{@code GET sites/{slug}/data/plugin/xp-activity}: the clan's XP per day and busy hours of the week from the RuneClans hub, as clan
 *   totals only, for the activity heatmap widget (T-583)</li>
 *   <li>{@code GET sites/{slug}/data/{route}}: that site's clan data, the same answers the clan site's own routes give the home server, for
 *   every route in {@link SiteApi#TENANT_ROUTES} (members, overview, leaderboard, recap, feed, records, citadel-grid, history, coffer, polls,
 *   signups, events, pvm, drops, bosses, boss, item, drop-log, member, member/skill, member/recap, notifications; T-364) with the same query
 *   parameters. {@code recap} is the clan's; {@code member/recap} is one member's, shown to that member (the {@value RecapGate#VIEWER_HEADER}
 *   header) and to anyone else only once they have opened it ({@link RecapGate}), otherwise {@code {"private": true}}. For a bot-less site
 *   (the bot isn't in its server) the same answers come from the stats stored by server id, a member recap is always private there (nobody
 *   can be told to be its member), and the Discord-only routes ({@link SiteApi#DISCORD_ROUTES}) answer {@code {"needsBot": true}}</li>
 * </ul>
 */
@BService
public class DirectoryClanSiteRoutes implements DirectoryRouteModule {
    private final ClanSiteService sites;
    private final SiteApi siteApi;
    private final ClanProfileRepository profiles;
    private final ClanSiteLayoutRepository layouts;
    private final ClanSitePluginFeed pluginFeed;

    public DirectoryClanSiteRoutes(ClanSiteService sites, SiteApi siteApi, ClanProfileRepository profiles, ClanSiteLayoutRepository layouts,
                                   ClanSitePluginFeed pluginFeed) {
        this.sites = sites;
        this.siteApi = siteApi;
        this.profiles = profiles;
        this.layouts = layouts;
        this.pluginFeed = pluginFeed;
    }

    private static int limit(DirectoryRequest request) {
        try {
            String raw = request.query("limit");
            return raw == null ? 10 : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        String[] parts = request.parts();
        if (!request.head().equals("sites")) return false;
        if (parts.length == 5 && parts[2].equals("data") && parts[3].equals("plugin")) {
            if (!request.requireGet()) return true;
            Optional<ClanSite> site = request.viewable(parts[1]).flatMap(p -> sites.visible(p.guildId()));
            DataObject feed = site.isEmpty() ? null : parts[4].equals("xp-activity") ? pluginFeed.xpActivity(site.get().guildId())
                    : pluginFeed.feed(site.get().guildId(), parts[4], limit(request));
            if (feed == null) request.sendError(404, "Not found");
            else request.send(200, feed);
            return true;
        }
        // Only the tenant routes are claimed here, so other modules' data routes (data/discord/*, T-365) still reach their module.
        if ((parts.length == 4 || parts.length == 5) && parts[2].equals("data")
                && SiteApi.TENANT_ROUTES.contains(parts.length == 4 ? parts[3] : parts[3] + "/" + parts[4])) {
            if (!request.requireGet()) return true;
            String route = parts.length == 4 ? parts[3] : parts[3] + "/" + parts[4]; // member/skill is the one two-part answer
            Optional<ClanSite> site = request.viewable(parts[1]).flatMap(p -> sites.visible(p.guildId()));
            Guild guild = site.isEmpty() ? null : request.jda().getGuildById(site.get().guildId());
            // A bot-less site's clan data is stored by server id like any other; its Discord-only routes answer {"needsBot": true}.
            boolean botless = guild == null && site.isPresent() && sites.botless(site.get().guildId());
            DataObject data = guild != null ? siteApi.tenant(guild, route, request.exchange())
                    : botless ? siteApi.tenant(site.get().guildId(), null, route, request.exchange()) : null;
            if (data == null) request.sendError(404, "Not found");
            else request.send(200, data);
            return true;
        }
        if (parts.length == 2) {
            if (!request.requireGet()) return true;
            Optional<ClanProfile> profile = request.viewable(parts[1]);
            Optional<ClanSite> site = profile.flatMap(p -> sites.visible(p.guildId()));
            if (site.isPresent()) {
                request.send(200, siteJson(request, profile.get(), site.get()));
                return true;
            }
            // An address the clan gave up less than ProfileRules.SLUG_GRACE ago (T-290): say where its site is now, so the old address forwards.
            String movedTo = profile.isPresent() ? null : movedTo(request, parts[1]);
            if (movedTo != null) request.send(200, DataObject.empty().put("movedTo", movedTo));
            else request.sendError(404, "No such site");
            return true;
        }
        return false;
    }

    /** The current address of the clan that gave up {@code slug} recently, when its site is still published there; null otherwise. */
    private String movedTo(DirectoryRequest request, String slug) {
        return profiles.slugHolder(slug)
                .flatMap(profiles::get)
                .map(ClanProfile::slug)
                .filter(now -> now != null && !now.equals(slug))
                .filter(now -> request.viewable(now).flatMap(p -> sites.visible(p.guildId())).isPresent())
                .orElse(null);
    }

    /** The public side of a clan's website: its clan (as the directory shows it), its look and its pages. Never a Discord id. */
    private DataObject siteJson(DirectoryRequest request, ClanProfile p, ClanSite site) {
        DataArray blocks = DataArray.empty();
        for (ClanSiteBlock b : sites.blocks(site.guildId())) {
            blocks.add(DataObject.empty().put("page", b.page()).put("position", b.position()).put("kind", b.kind().name()).put("body", jsonObject(b.body())));
        }
        return DataObject.empty().put("slug", p.slug()).put("clan", request.clanJson(p)).put("template", site.template().name())
                .put("botless", sites.botless(site.guildId()) && request.jda().getGuildById(site.guildId()) == null)
                .put("theme", jsonObject(site.theme())).put("pages", jsonArray(site.pages())).put("blocks", blocks)
                .put("publishedAt", site.publishedAt() == null ? null : site.publishedAt().toString())
                .put("plan", com.younglings.bot.premium.Billing.planJson(site.guildId(), request.jda().getGuildById(site.guildId()) != null)) // T-366
                .put("layout", layouts.byGuild(site.guildId()).map(l -> l.published()).filter(raw -> raw != null && !raw.isBlank()).map(DirectoryClanSiteRoutes::jsonObject).orElse(null));
    }

    private static DataObject jsonObject(String raw) {
        try {
            return DataObject.fromJson(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (RuntimeException e) {
            return DataObject.empty();
        }
    }

    private static DataArray jsonArray(String raw) {
        try {
            return DataArray.fromJson(raw == null || raw.isBlank() ? "[]" : raw);
        } catch (RuntimeException e) {
            return DataArray.empty();
        }
    }
}
