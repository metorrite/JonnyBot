package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clansite.ClanSite;
import com.younglings.bot.clansite.ClanSiteBlock;
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
 *   <li>{@code GET sites/{slug}}: the site's look, pages and content blocks</li>
 *   <li>{@code GET sites/{slug}/data/{members|overview|leaderboard|recap}}: that site's clan data, the same answers the clan site's own
 *   routes give the home server ({@link SiteApi#TENANT_ROUTES}); a recap is always the clan's, never one member's</li>
 * </ul>
 */
@BService
public class DirectoryClanSiteRoutes implements DirectoryRouteModule {
    private final ClanSiteService sites;
    private final SiteApi siteApi;

    public DirectoryClanSiteRoutes(ClanSiteService sites, SiteApi siteApi) {
        this.sites = sites;
        this.siteApi = siteApi;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        String[] parts = request.parts();
        if (!request.head().equals("sites")) return false;
        if (parts.length == 4 && parts[2].equals("data")) {
            if (!request.requireGet()) return true;
            Optional<ClanSite> site = request.viewable(parts[1]).flatMap(p -> sites.visible(p.guildId()));
            Guild guild = site.isEmpty() ? null : request.jda().getGuildById(site.get().guildId());
            DataObject data = guild == null ? null : siteApi.tenant(guild, parts[3], request.exchange());
            if (data == null) request.sendError(404, "Not found");
            else request.send(200, data);
            return true;
        }
        if (parts.length == 2) {
            if (!request.requireGet()) return true;
            Optional<ClanProfile> profile = request.viewable(parts[1]);
            Optional<ClanSite> site = profile.flatMap(p -> sites.visible(p.guildId()));
            if (site.isEmpty()) request.sendError(404, "No such site");
            else request.send(200, siteJson(request, profile.get(), site.get()));
            return true;
        }
        return false;
    }

    /** The public side of a clan's website: its clan (as the directory shows it), its look and its pages. Never a Discord id. */
    private DataObject siteJson(DirectoryRequest request, ClanProfile p, ClanSite site) {
        DataArray blocks = DataArray.empty();
        for (ClanSiteBlock b : sites.blocks(site.guildId())) {
            blocks.add(DataObject.empty().put("page", b.page()).put("position", b.position()).put("kind", b.kind().name()).put("body", jsonObject(b.body())));
        }
        return DataObject.empty().put("slug", p.slug()).put("clan", request.clanJson(p)).put("template", site.template().name())
                .put("theme", jsonObject(site.theme())).put("pages", jsonArray(site.pages())).put("blocks", blocks)
                .put("publishedAt", site.publishedAt() == null ? null : site.publishedAt().toString());
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
