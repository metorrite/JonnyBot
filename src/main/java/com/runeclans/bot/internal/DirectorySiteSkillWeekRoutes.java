package com.runeclans.bot.internal;

import com.runeclans.bot.clansite.ClanSiteService;
import com.runeclans.bot.events.SkillWeekService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.io.IOException;

/**
 * {@code GET sites/{slug}/data/skill-week?limit=}: the clan website's "Skill of the week" widget (T-541), only while the site is published: this
 * week's skill and standings by XP gained, and last week's winners ({@link SkillWeekService#publicView}: only members who chose to show their
 * RuneSync activity on clan sites are named with their XP, and a competition's standings kept to staff or hidden until it ends aren't shown). A
 * server that never switched it on answers {@code enabled: false} and nothing else to show.
 */
@BService
public class DirectorySiteSkillWeekRoutes implements DirectoryRouteModule {
    private final ClanSiteService sites;
    private final SkillWeekService weeks;
    private final RateLimiter perSite = new RateLimiter(10, 50);

    public DirectorySiteSkillWeekRoutes(ClanSiteService sites, SkillWeekService weeks) {
        this.sites = sites;
        this.weeks = weeks;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        String[] parts = request.parts();
        if (!request.head().equals("sites") || parts.length != 4 || !parts[2].equals("data") || !parts[3].equals("skill-week")) return false;
        if (!request.requireGet()) return true;
        var profile = request.viewable(parts[1]).filter(p -> sites.visible(p.guildId()).isPresent());
        if (profile.isEmpty()) {
            request.sendError(404, "Not found");
            return true;
        }
        if (!perSite.tryAcquire(parts[1])) {
            request.sendError(429, "Too many requests");
            return true;
        }
        request.send(200, weeks.publicView(profile.get().guildId(), limit(request.query("limit"))).remove("rota").remove("next").remove("available"));
        return true;
    }

    static int limit(String raw) {
        try {
            return Math.max(1, Math.min(SkillWeekService.MAX_SHOWN, Integer.parseInt(raw == null ? "10" : raw.strip())));
        } catch (NumberFormatException e) {
            return 10;
        }
    }
}
