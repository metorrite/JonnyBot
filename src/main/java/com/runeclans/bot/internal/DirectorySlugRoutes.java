package com.runeclans.bot.internal;

import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.clanprofile.ProfileRules;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.util.Locale;

/**
 * {@code GET slug-check?slug=x} (T-290): whether a clan could take an address, which is also its website's subdomain. The website builder
 * asks before sending someone to the dashboard to claim it; the dashboard's save checks again (that is the claim). Answers
 * {@code {slug, available, problem}}: the address as it would be stored, and why not in plain words when it can't be had (badly formed,
 * reserved, unsuitable, or used by a clan or held for one that just gave it up). Says nothing about who has it.
 */
@BService
public class DirectorySlugRoutes implements DirectoryRouteModule {
    private final ClanProfileRepository profiles;

    public DirectorySlugRoutes(ClanProfileRepository profiles) {
        this.profiles = profiles;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        if (!request.head().equals("slug-check") || request.parts().length != 1) return false;
        if (!request.requireGet()) return true;
        String raw = request.query("slug");
        String slug = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (slug.length() > 64) slug = slug.substring(0, 64);
        String problem = ProfileRules.slugProblem(slug);
        // -1: no server is excepted, so a held address shows as taken to everyone (its clan can still take it back on the dashboard)
        if (problem == null && profiles.slugTaken(slug, -1)) problem = "Another clan uses that address, or gave it up recently. Choose another.";
        request.send(200, DataObject.empty().put("slug", slug).put("available", problem == null).put("problem", problem));
        return true;
    }
}
