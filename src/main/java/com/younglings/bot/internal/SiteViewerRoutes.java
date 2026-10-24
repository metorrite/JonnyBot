package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clansite.ClanSiteService;
import com.younglings.bot.clansite.SiteRoleGuard;
import com.younglings.bot.clansite.SiteRoles;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Who is looking at a clan website (T-368, docs/BUILDER-V3.md 8.2):
 * {@code GET /internal/directory/sites/{slug}/viewer?userId=} answers {@code {role, inGuild, staff, canEdit, canPublish, linkedRsns, guildId}}.
 * The website must take {@code userId} from the signed-in session only, never from the visitor (the same rule as the "my link" route),
 * and must not keep the answer across people; the bot caches it for a minute per person and site. Without a {@code userId} the answer is
 * {@code visitor}. Ten answers a second per site at most. A bot-less site (the bot isn't in its server) answers from its stored roles alone
 * ({@link SiteRoleGuard#viewerWithoutBot}), with {@code botless: true}. The other {@code sites/...} routes are {@link DirectoryClanSiteRoutes}'.
 */
@BService
public class SiteViewerRoutes implements DirectoryRouteModule {
    private static final Pattern SNOWFLAKE = Pattern.compile("[0-9]{15,20}");

    private final SiteRoleGuard guard;
    private final ClanSiteService sites;
    private final RateLimiter perSite = new RateLimiter(10, 100);

    public SiteViewerRoutes(SiteRoleGuard guard, ClanSiteService sites) {
        this.guard = guard;
        this.sites = sites;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        String[] parts = request.parts();
        if (parts.length != 3 || !request.head().equals("sites") || !parts[2].equals("viewer")) return false;
        if (!request.requireGet()) return true;
        Optional<ClanProfile> profile = request.viewable(parts[1]);
        Guild guild = profile.map(p -> request.jda().getGuildById(p.guildId())).orElse(null);
        // a bot-less site (the bot isn't in its server): owner and editors by user id, everyone else a guest
        Long botless = guild == null && profile.isPresent() && sites.botless(profile.get().guildId()) ? profile.get().guildId() : null;
        if (guild == null && botless == null) {
            request.sendError(404, "No such site");
            return true;
        }
        String siteKey = guild != null ? guild.getId() : Long.toString(botless);
        if (!perSite.tryAcquire(siteKey)) {
            request.sendError(429, "Too many requests");
            return true;
        }
        String userId = request.query("userId");
        if (userId == null || userId.isEmpty()) {
            request.send(200, json(null, null));
            return true;
        }
        if (!SNOWFLAKE.matcher(userId).matches()) {
            request.sendError(400, "Bad userId");
            return true;
        }
        if (botless != null) {
            request.send(200, json(guard.viewerWithoutBot(botless, Long.parseLong(userId)), siteKey).put("botless", true));
            return true;
        }
        SiteRoleGuard.Viewer viewer;
        try {
            viewer = guard.viewer(guild, Long.parseLong(userId));
        } catch (RuntimeException e) {
            request.sendError(503, "Discord didn't answer; try again");
            return true;
        }
        request.send(200, json(viewer, guild.getId()));
        return true;
    }

    /**
     * The answer. {@code guildId} is given only to the site's editors and above (people who are in that server and run its site), so the
     * website can open the editor and the site admin, which use the server-keyed {@code /internal/admin/} routes; nobody else ever gets it.
     */
    static DataObject json(SiteRoleGuard.Viewer viewer, String guildId) {
        if (viewer == null) {
            return DataObject.empty().put("role", "visitor").put("inGuild", false).put("staff", false).put("canEdit", false).put("canPublish", false)
                    .put("linkedRsns", DataArray.empty()).putNull("guildId");
        }
        return DataObject.empty().put("role", viewer.role().key()).put("inGuild", viewer.inGuild()).put("staff", viewer.staff())
                .put("canEdit", viewer.canEdit()).put("canPublish", viewer.canPublish()).put("linkedRsns", DataArray.fromCollection(viewer.linkedRsns()))
                .put("guildId", viewer.role().atLeast(SiteRoles.Role.EDITOR) ? guildId : null);
    }
}
