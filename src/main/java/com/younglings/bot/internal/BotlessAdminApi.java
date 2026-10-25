package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.access.AccessArea;
import com.younglings.bot.access.AccessGrantService;
import com.younglings.bot.clansite.BotlessSiteRepository;
import com.younglings.bot.clansite.SiteRoleGuard;
import com.younglings.bot.clansite.SiteRoles;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.internal.TicketAdminJson.BadRequest;
import com.younglings.bot.notice.BotOwners;
import com.younglings.bot.runescape.ClanSyncScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The dashboard's routes for a server the bot is NOT in (a bot-less clan website): {@link InternalApiServer} sends a request here instead of
 * answering "the bot isn't in that server" when {@code X-Guild-Id} names such a server. Nothing about the person can be asked of Discord there,
 * so every request must carry the website's signed sign-in facts ({@link ActorFacts}: who they are, whether they own or manage the server), and
 * the person must be on the site builder beta list ({@link AccessArea#SITE_BUILDER_BETA}, or be the bot's owner). Missing or invalid facts are a
 * 403; so is someone not on the list.
 * <p>
 * Only what a website needs works here, all of it stored by server id:
 * <ul>
 *   <li>{@code GET whoami}: 200 always (like the bot-present one): {@code {allowed, tier, botless: true, beta, siteRole, ...}}</li>
 *   <li>{@code clan-site/...}: the whole site builder ({@link ClanSiteAdminApi}), with the site role from the facts: owner and admins from
 *   them, editors by Discord user id; Discord roles answer as unavailable</li>
 *   <li>{@code GET|PUT clan-site/clan-name} {@code {clanName}}: the RuneScape clan the site shows (stored as the server's clan name, which the
 *   daily clan sync and the stats read); not verified while the builder is in beta</li>
 *   <li>{@code GET setup}, and {@code PUT setup} with only {@code {clanName}}: the same clan name, for the website's guided setup</li>
 *   <li>{@code GET|PUT clan-profile}: the clan profile (its address, name and visibility); {@code clan-profile/verify} needs the bot</li>
 *   <li>{@code PUT|DELETE images/site/{slot}}: the site's logo and banner; {@code site-pictures...}: its picture library</li>
 *   <li>{@code GET structure}: empty lists with {@code botless: true} (no roles or channels can be seen); {@code GET audit}: the site's changes</li>
 * </ul>
 * Anything else answers 409 {@code {"needsBot": true}}. Every write is rate limited per person and written to the server's audit log.
 */
@BService
public class BotlessAdminApi {
    private static final Logger log = LoggerFactory.getLogger(BotlessAdminApi.class);
    private static final String PREFIX = "/internal/admin/";
    private static final String ACTOR_HEADER = "X-Actor-Id";
    static final int MAX_CLAN_NAME = 20;
    /** A RuneScape clan name: letters, digits, spaces, hyphens and underscores, up to {@link #MAX_CLAN_NAME}. */
    private static final Pattern CLAN_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _-]{0," + (MAX_CLAN_NAME - 1) + "}");
    static final String NEEDS_BOT = "That needs RuneClan Bot in the server. Add the bot to use it; the website itself works without it.";
    static final String NOT_ON_BETA = "Running a clan website without the bot is in beta. Ask the RuneClans team to add your Discord account to the beta list.";

    private final ClanSiteAdminApi clanSite;
    private final ClanProfileAdminApi clanProfile;
    private final SitePictureAdminApi pictures;
    private final ImageAdminApi images;
    private final AdminOpsApi ops;
    private final AdminToolsStore auditStore;
    private final SiteRoleGuard siteRoles;
    private final BotlessSiteRepository botless;
    private final GuildSettingsService guildSettings;
    private final AccessGrantService accessGrants;
    private final BotOwners owners;
    private final ClanSyncScheduler clanSync;
    private final RateLimiter writes = new RateLimiter(20, 1_000);
    /** Unix seconds now; replaceable so tests can check expiry without waiting. */
    LongSupplier clock = () -> Instant.now().getEpochSecond();

    public BotlessAdminApi(ClanSiteAdminApi clanSite, ClanProfileAdminApi clanProfile, SitePictureAdminApi pictures, ImageAdminApi images, AdminOpsApi ops,
                           AdminToolsStore auditStore, SiteRoleGuard siteRoles, BotlessSiteRepository botless, GuildSettingsService guildSettings,
                           AccessGrantService accessGrants, BotOwners owners, ClanSyncScheduler clanSync) {
        this.clanSync = clanSync;
        this.clanSite = clanSite;
        this.clanProfile = clanProfile;
        this.pictures = pictures;
        this.images = images;
        this.ops = ops;
        this.auditStore = auditStore;
        this.siteRoles = siteRoles;
        this.botless = botless;
        this.guildSettings = guildSettings;
        this.accessGrants = accessGrants;
        this.owners = owners;
    }

    /** One request about {@code guildId}, a server the bot isn't in. The internal secret was already checked; {@code secret} also keys the facts. */
    public void handle(HttpExchange exchange, long guildId, JDA jda, String secret) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String route = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
            if (route.endsWith("/")) route = route.substring(0, route.length() - 1);
            String[] parts = route.isEmpty() ? new String[0] : route.split("/");
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

            ActorFacts facts;
            try {
                facts = ActorFacts.verify(exchange.getRequestHeaders().getFirst(ActorFacts.HEADER), exchange.getRequestHeaders().getFirst(ActorFacts.SIG_HEADER),
                        secret, guildId, clock.getAsLong());
            } catch (ActorFacts.Invalid e) {
                InternalApiServer.sendJson(exchange, 403, DataObject.empty().put("error", e.getMessage()).put("botless", true));
                return;
            }
            String claimed = exchange.getRequestHeaders().getFirst(ACTOR_HEADER);
            if (claimed != null && !claimed.isBlank() && !claimed.strip().equals(Long.toString(facts.userId()))) {
                InternalApiServer.sendJson(exchange, 403, DataObject.empty().put("error", "Your sign-in facts are for someone else. Sign in again and retry.").put("botless", true));
                return;
            }

            java.util.Map<Long, Long> staff = ServerFacts.serversOf(exchange.getRequestHeaders().getFirst(ServerFacts.HEADER),
                    exchange.getRequestHeaders().getFirst(ServerFacts.SIG_HEADER), secret, facts.userId(), clock.getAsLong());
            SiteCaller caller = SiteCaller.botless(facts, siteRoles.factsWithoutBot(jda, facts.userId(), facts.owner(), facts.admin(), staff), jda);
            // overall admins (N5) and the people this site's owner let into its admin panel come in without being on the beta list themselves
            boolean onBeta = onBeta(jda, facts.userId()) || caller.facts().overallAdmin()
                    || SiteRoles.siteAdmin(caller.facts(), siteRoles.settings(guildId));
            if (parts.length == 1 && parts[0].equals("whoami")) {
                AdminRouteExtension.requireMethod(method, "GET");
                InternalApiServer.sendJson(exchange, 200, whoami(caller, facts, onBeta));
                return;
            }
            if (!onBeta) {
                InternalApiServer.sendJson(exchange, 403, DataObject.empty().put("error", NOT_ON_BETA).put("botless", true).put("notOnBeta", true));
                return;
            }
            // The first time someone who owns or manages the server uses it, the server's site becomes a bot-less one (into the builder beta,
            // the clan sync, and never the leave grace on its plan).
            if (facts.owner() || facts.admin()) botless.register(guildId, facts.userId());
            if (!method.equals("GET") && !writes.tryAcquire(Long.toString(facts.userId()))) throw new ApiError(429, "You're doing that too fast. Wait a moment and try again.");

            DataObject result = route(exchange, caller, method, parts);
            if (result == null) {
                InternalApiServer.sendJson(exchange, 404, DataObject.empty().put("error", "Not found"));
                return;
            }
            if (!method.equals("GET")) auditStore.audit(guildId, Long.toString(facts.userId()), null, method, route, 200);
            InternalApiServer.sendJson(exchange, 200, result);
        } catch (NeedsBot e) {
            InternalApiServer.sendJson(exchange, 409, DataObject.empty().put("error", e.getMessage()).put("needsBot", true).put("botless", true));
        } catch (ApiError e) {
            DataObject body = DataObject.empty().put("error", e.getMessage());
            if (!e.problems.isEmpty()) body.put("problems", DataArray.fromCollection(e.problems));
            InternalApiServer.sendJson(exchange, e.status, body);
        } catch (BadRequest e) {
            InternalApiServer.sendJson(exchange, 400, DataObject.empty().put("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Bot-less admin request {} {} failed", exchange.getRequestMethod(), exchange.getRequestURI().getPath(), e);
            InternalApiServer.sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    /**
     * {@code GET /internal/admin/beta-testers/me}: {@code {betaTester}}, whether the asker ({@code X-Actor-Id}, trusted like the {@code guilds}
     * list once the internal secret was checked) may run a clan website without the bot: on the beta list, or the bot's owner. It names no
     * server, so anyone signed in may ask, whether or not they share a server with the bot. The internal secret was already checked.
     */
    public void handleBetaTesterMe(HttpExchange exchange, JDA jda) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            InternalApiServer.sendJson(exchange, 405, DataObject.empty().put("error", "Method not allowed"));
            return;
        }
        String raw = exchange.getRequestHeaders().getFirst(ACTOR_HEADER);
        long userId = 0;
        if (raw != null && raw.strip().matches("[0-9]{15,20}")) {
            try {
                userId = Long.parseLong(raw.strip());
            } catch (NumberFormatException e) {
                userId = 0;
            }
        }
        InternalApiServer.sendJson(exchange, 200, DataObject.empty().put("betaTester", userId != 0 && onBeta(jda, userId)));
    }

    private boolean onBeta(JDA jda, long userId) {
        return owners.isOwner(jda, userId) || accessGrants.allows(jda, AccessArea.SITE_BUILDER_BETA, userId);
    }

    /** {@code null} for a path that doesn't exist; a {@link #needsBot} refusal for one that exists only with the bot. */
    private DataObject route(HttpExchange exchange, SiteCaller c, String method, String[] parts) {
        if (parts.length == 0) return null;
        Supplier<DataObject> body = () -> body(exchange);
        switch (parts[0]) {
            case "clan-site" -> {
                if (parts.length == 2 && parts[1].equals("clan-name")) return clanName(c, method, body);
                DataObject answer = clanSite.route(c, method, parts, body);
                if (answer == null) return null;
                if (parts.length == 2 && parts[1].equals("delete")) botless.remove(c.guildId());
                if (parts.length == 1) answer.put("botless", true).put("clanName", guildSettings.getEffective(c.guildId()).clanName());
                return answer;
            }
            case "setup" -> {
                if (parts.length != 1) throw needsBot();
                return setup(c, method, body);
            }
            case "clan-profile" -> {
                if (parts.length != 1) throw needsBot();
                require(c, SiteRoles.Action.SETTINGS);
                if (method.equals("GET")) return clanProfile.get(c.guildId(), null).put("botless", true);
                AdminRouteExtension.requireMethod(method, "PUT");
                return clanProfile.save(c.guildId(), null, c.userId(), body.get()).put("botless", true);
            }
            case SitePictureAdminApi.PATH -> {
                return pictures.route(c, method, parts, body);
            }
            case "images" -> {
                if (parts.length != 3 || !parts[1].equals("site")) throw needsBot();
                require(c, SiteRoles.Action.SETTINGS);
                if (method.equals("PUT")) return images.upload(c.guildId(), c.userId(), parts[1], parts[2], body.get());
                AdminRouteExtension.requireMethod(method, "DELETE");
                return images.remove(c.guildId(), c.userId(), parts[1], parts[2]);
            }
            case "structure" -> {
                if (parts.length != 1) return null;
                AdminRouteExtension.requireMethod(method, "GET");
                require(c, SiteRoles.Action.EDIT);
                return DataObject.empty().put("roles", DataArray.empty()).put("categories", DataArray.empty()).put("channels", DataArray.empty())
                        .put("forums", DataArray.empty()).put("threads", DataArray.empty()).put("botless", true)
                        .put("unavailable", ClanSiteAdminApi.DISCORD_ROLES_UNAVAILABLE);
            }
            case "audit" -> {
                AdminRouteExtension.requireMethod(method, "GET");
                require(c, SiteRoles.Action.SETTINGS);
                return ops.audit(c.guildId(), query(exchange, "limit"), query(exchange, "actor"), query(exchange, "q"));
            }
            default -> throw needsBot();
        }
    }

    /** {@code GET}: the clan the site shows; {@code PUT {clanName}} (null or empty clears it): admin. */
    private DataObject clanName(SiteCaller c, String method, Supplier<DataObject> body) {
        if (method.equals("GET")) {
            require(c, SiteRoles.Action.EDIT);
            return DataObject.empty().put("clanName", guildSettings.getEffective(c.guildId()).clanName()).put("verified", false);
        }
        AdminRouteExtension.requireMethod(method, "PUT");
        String saved = saveClanName(c, body.get());
        return DataObject.empty().put("clanName", saved).put("verified", false);
    }

    /**
     * {@code GET setup}: the server's setup as far as a bot-less site has one ({@code clanName}; no clan features without the bot). {@code PUT setup}
     * {@code {clanName}}: the guided setup's Clan info step, the same as {@code PUT clan-site/clan-name}; any other setting needs the bot.
     */
    private DataObject setup(SiteCaller c, String method, Supplier<DataObject> body) {
        if (method.equals("GET")) require(c, SiteRoles.Action.EDIT);
        else {
            AdminRouteExtension.requireMethod(method, "PUT");
            DataObject b = body.get();
            if (!b.keys().equals(java.util.Set.of("clanName"))) throw needsBot();
            saveClanName(c, b);
        }
        var settings = guildSettings.getEffective(c.guildId());
        return DataObject.empty().put("clanName", settings.clanName()).put("clanEnabled", false).put("clanActive", false)
                .put("inviteUrl", settings.inviteUrl()).put("verified", false).put("botless", true).put("botPresent", false);
    }

    /** Stores {@code {clanName}} (null or empty clears it) as the server's clan name: admin. Answers the name now stored, or null. */
    private String saveClanName(SiteCaller c, DataObject b) {
        require(c, SiteRoles.Action.SETTINGS);
        String raw = b.isNull("clanName") ? "" : b.getString("clanName", "").strip().replaceAll("\\s+", " ");
        String problem = clanNameProblem(raw);
        if (problem != null) throw new ApiError(400, "The clan name can't be saved.", List.of(problem));
        guildSettings.updateClanName(c.guildId(), raw.isEmpty() ? null : raw);
        if (!raw.isEmpty()) clanSync.syncSoon(c.guildId()); // the stats fill in now, not at the next midnight
        log.info("Bot-less site of guild {}: {} set the clan name to '{}'", c.guildId(), c.userId(), raw);
        return raw.isEmpty() ? null : raw;
    }

    /** Why a clan name can't be used, or null (an empty one clears it). */
    static String clanNameProblem(String name) {
        if (name.isEmpty()) return null;
        if (name.length() > MAX_CLAN_NAME) return "A RuneScape clan name is at most " + MAX_CLAN_NAME + " characters.";
        if (!CLAN_NAME.matcher(name).matches()) return "A RuneScape clan name has only letters, numbers, spaces, hyphens and underscores.";
        return null;
    }

    /**
     * Who is asking, for the website to decide what to show. {@code allowed} means they may use this server's site builder (on the beta list and
     * at least an editor of the site); the rest of the dashboard needs the bot whatever it says.
     */
    private DataObject whoami(SiteCaller c, ActorFacts facts, boolean onBeta) {
        SiteRoles.Role role = c.role(siteRoles);
        boolean manager = facts.owner() || facts.admin();
        boolean allowed = onBeta && role.atLeast(SiteRoles.Role.EDITOR);
        DataObject who = DataObject.empty().put("allowed", allowed).put("tier", allowed && manager ? "ADMIN" : "NONE").put("botless", true)
                .put("id", Long.toString(facts.userId())).put("siteRole", role.key()).put("beta", onBeta).put("notOnBeta", !onBeta)
                .put("isOwner", owners.isOwner(c.jda(), facts.userId()));
        if (allowed) who.put("guildId", Long.toString(c.guildId()));
        DataObject areas = DataObject.empty();
        for (AccessArea area : AccessArea.values()) areas.put(area.key(), area == AccessArea.SITE_BUILDER_BETA ? onBeta : accessGrants.allows(c.jda(), area, facts.userId()));
        return who.put("areas", areas);
    }

    private void require(SiteCaller c, SiteRoles.Action action) {
        if (!c.may(siteRoles, action)) {
            throw new ApiError(403, "You can't do that on this website.", List.of(action == SiteRoles.Action.EDIT ? "Only the site's editors can do that."
                    : "Only the site's admins (the server's owner, or someone who may manage it) can change that."));
        }
    }

    /** The refusal for a route that only works with the bot in the server: 409 {@code {error, needsBot: true}} so the website can say why. */
    static NeedsBot needsBot() {
        return new NeedsBot();
    }

    static final class NeedsBot extends RuntimeException {
        NeedsBot() {
            super(NEEDS_BOT);
        }
    }

    private static DataObject body(HttpExchange exchange) {
        try (var in = exchange.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            return bytes.length == 0 ? DataObject.empty() : DataObject.fromJson(bytes);
        } catch (Exception e) {
            throw new ApiError(400, "The request body isn't valid JSON.");
        }
    }

    private static String query(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return null;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }
}
