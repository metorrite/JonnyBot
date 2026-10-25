package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.clansite.SiteRoleGuard;
import com.younglings.bot.clansite.SiteRoles;
import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The clan website's admin and editor routes for people the dashboard does NOT let into a server the bot is in (N5): overall RuneClans admins
 * (every site), the people a site's owner let into its admin panel (by Discord user id, a Discord role, or the staff-server rule) and the
 * site's editors. They need not be members of the server. {@link TicketAdminApi} asks {@link #caller} before refusing someone without
 * dashboard access; when it answers, only these paths open, each checked on the site role as usual:
 * <ul>
 *   <li>{@code clan-site...} ({@link ClanSiteAdminApi}, which checks the role each route needs) and {@code site-pictures...} (editors)</li>
 *   <li>{@code PUT|DELETE images/site/{slot}}: the site's logo and banner (site admins, like the bot-less site's)</li>
 *   <li>{@code GET structure}: the server's roles and channels for the pickers (site admins)</li>
 *   <li>{@code GET audit}: the site's own changes only ({@code q} is always {@code clan-site}; site admins)</li>
 * </ul>
 * Everyone else still gets the dashboard's 403.
 */
@BService
public class SiteAccessRoutes {
    private final SiteRoleGuard siteRoles;
    private final BotConfig config;
    /** Unix seconds now; replaceable so tests can check expiry without waiting. */
    LongSupplier clock = () -> Instant.now().getEpochSecond();

    public SiteAccessRoutes(SiteRoleGuard siteRoles, BotConfig config) {
        this.siteRoles = siteRoles;
        this.config = config;
    }

    static boolean sitePath(String[] parts) {
        if (parts.length < 1) return false;
        return switch (parts[0]) {
            case ClanSiteAdminApi.ROOT, SitePictureAdminApi.PATH -> true;
            case "structure", "audit" -> parts.length == 1;
            case "images" -> parts.length == 3 && parts[1].equals("site");
            default -> false;
        };
    }

    /** The action the path needs at the least before it is opened at all (each route then checks its own). */
    static SiteRoles.Action entry(String method, String[] parts) {
        return switch (parts[0]) {
            case "structure", "audit" -> method.equals("GET") ? SiteRoles.Action.SETTINGS : null;
            case "images" -> method.equals("PUT") || method.equals("DELETE") ? SiteRoles.Action.SETTINGS : null;
            default -> SiteRoles.Action.EDIT;
        };
    }

    /**
     * The asker as a site caller when this path is one of the site's and their site role lets them in, else null (the dashboard's 403 stands).
     * {@code member} is null when they aren't in the server.
     */
    SiteCaller caller(HttpExchange exchange, Guild guild, long userId, Member member, String method, String[] parts) {
        if (userId == 0 || !sitePath(parts)) return null;
        SiteRoles.Action needed = entry(method, parts);
        if (needed == null) return null;
        SiteRoles.Facts facts = siteRoles.facts(guild, userId, member).withStaffServers(staffServers(exchange, userId));
        SiteCaller caller = SiteCaller.site(guild, userId, member, facts);
        return caller.may(siteRoles, needed) ? caller : null;
    }

    /** The person's signed staff servers ({@link ServerFacts}), or none. */
    Map<Long, Long> staffServers(HttpExchange exchange, long userId) {
        return ServerFacts.serversOf(exchange.getRequestHeaders().getFirst(ServerFacts.HEADER), exchange.getRequestHeaders().getFirst(ServerFacts.SIG_HEADER),
                config.getInternalApiSecret(), userId, clock.getAsLong());
    }

    /** One of {@link #sitePath}'s routes for an admitted caller; {@code structure} and {@code audit} come from the dashboard's own code. */
    static DataObject route(SiteCaller c, String method, String[] parts, Supplier<DataObject> body, ClanSiteAdminApi clanSite, SitePictureAdminApi pictures,
                            ImageAdminApi images, Supplier<DataObject> structure, Supplier<DataObject> audit) {
        return switch (parts[0]) {
            case ClanSiteAdminApi.ROOT -> clanSite.route(c, method, parts, body);
            case SitePictureAdminApi.PATH -> pictures.route(c, method, parts, body);
            case "images" -> method.equals("PUT") ? images.upload(c.guildId(), c.userId(), parts[1], parts[2], body.get())
                    : images.remove(c.guildId(), c.userId(), parts[1], parts[2]);
            case "structure" -> structure.get();
            case "audit" -> audit.get();
            default -> null;
        };
    }
}
