package com.younglings.bot.internal;

import com.younglings.bot.access.AccessLevel;
import com.younglings.bot.beta.BetaGuildRepository;
import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.clanprofile.ProfileRules;
import com.younglings.bot.clansite.ClanSite;
import com.younglings.bot.clansite.ClanSiteLayoutRepository;
import com.younglings.bot.clansite.ClanSiteRepository;
import com.younglings.bot.clansite.ClanSiteService;
import com.younglings.bot.clansite.SiteRoleGuard;
import com.younglings.bot.discord.DiscordLookups;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.notice.BotOwners;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The bot owner's console over every clan website (T-423, runeclans.com/admin): a privilege over other servers, so every route is the
 * owner's alone and runs in the console's own server (the home server); the server it is about is in the path. Reached only through
 * {@link TicketAdminApi}, which has already checked the asker and audit-logs every change.
 * <ul>
 *   <li>{@code GET owner-sites}: every server with a clan profile, a site or a layout, plus the servers the bot is in (for a test site);
 *   {@code botless} marks a site run without the bot (docs/BOTLESS-SITES.md)</li>
 *   <li>{@code GET owner-sites/{guildId}}: one of them</li>
 *   <li>{@code POST owner-sites/{guildId}/address} {@code {slug?, displayName?}}: the address with the same reserved-word and taken checks
 *   as the clan's own Clan profile page (an unchanged address is kept)</li>
 *   <li>{@code POST owner-sites/{guildId}/owner} {@code {userId|null}}: hand the site to someone in the server (null: the server owner)</li>
 *   <li>{@code POST owner-sites/{guildId}/disable} {@code {reason?}} and {@code .../enable}: off and on again through the owner's
 *   takedown, so nothing is lost and the clan sees why (the soft "delete")</li>
 *   <li>{@code POST owner-sites/{guildId}/delete} {@code {confirm}}: deletes the site (settings, blocks, layouts, roles) for good; the clan
 *   profile and its address stay. {@code confirm} is the address (or "delete" when it has none)</li>
 *   <li>{@code POST owner-sites/{guildId}/reset-draft}: the draft goes back to the live copy, or away when nothing was published</li>
 *   <li>{@code POST owner-sites/test} {@code {guildId}}: a test site in a server the bot is in: an unlisted clan profile with a
 *   {@code test-} address if it has none, the site switched on, and the server on the beta list</li>
 * </ul>
 */
@BService
public class SiteOwnerAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(SiteOwnerAdminApi.class);
    static final String ROOT = "owner-sites";
    static final String DEFAULT_REASON = "Switched off by the RuneClans team.";
    private static final int MAX_REASON = 300;

    private final ClanProfileRepository profiles;
    private final ClanSiteRepository sites;
    private final ClanSiteLayoutRepository layouts;
    private final ClanSiteService siteService;
    private final SiteRoleGuard siteRoles;
    private final BetaGuildRepository beta;
    private final BotOwners owners;

    public SiteOwnerAdminApi(ClanProfileRepository profiles, ClanSiteRepository sites, ClanSiteLayoutRepository layouts, ClanSiteService siteService,
                             SiteRoleGuard siteRoles, BetaGuildRepository beta, BotOwners owners) {
        this.profiles = profiles;
        this.sites = sites;
        this.layouts = layouts;
        this.siteService = siteService;
        this.siteRoles = siteRoles;
        this.beta = beta;
        this.owners = owners;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals(ROOT)) return null;
        requireOwner(guild, actor);
        return routes(guild, actor.getIdLong(), method, parts, body);
    }

    /**
     * The same routes for the overall admin panel (N5, {@link OverallAdminApi}), which has already checked the asker is an overall admin;
     * {@code home} is the bot's home server (for the server list and the words), {@code parts} start with {@link #ROOT}.
     */
    DataObject routes(Guild guild, long actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals(ROOT)) return null;
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return list(guild);
        }
        if (parts.length == 2 && parts[1].equals("test")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return testSite(guild, actor, body.get());
        }
        long target = parseId(parts[1]);
        if (target == 0) throw new ApiError(400, Text.of(guild, "dashboard.the_website_can_t_be_saved"), List.of("That isn't a server ID."));
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "GET");
            DataObject found = one(guild, target);
            boolean nothing = found.isNull("slug") && found.isNull("site") && found.isNull("layout") && !found.getBoolean("botPresent");
            if (nothing) throw new ApiError(404, Text.of(guild, "dashboard.the_bot_isn_t_in_that_server"));
            return found;
        }
        if (parts.length != 3) return null;
        AdminRouteExtension.requireMethod(method, "POST");
        return switch (parts[2]) {
            case "address" -> address(guild, actor, target, body.get());
            case "owner" -> owner(guild, actor, target, body.get());
            case "disable" -> setDisabled(guild, actor, target, body.get().getString("reason", ""));
            case "enable" -> setDisabled(guild, actor, target, null);
            case "delete" -> delete(guild, actor, target, body.get().getString("confirm", ""));
            case "reset-draft" -> resetDraft(guild, actor, target);
            default -> null;
        };
    }

    // ---------- read ----------

    DataObject list(Guild guild) {
        Map<Long, ClanProfile> byProfile = profiles.all().stream().collect(Collectors.toMap(ClanProfile::guildId, Function.identity(), (a, b) -> a));
        Map<Long, ClanSite> bySite = sites.all().stream().collect(Collectors.toMap(ClanSite::guildId, Function.identity(), (a, b) -> a));
        Map<Long, ClanSiteLayoutRepository.Summary> byLayout = layouts.summaries().stream()
                .collect(Collectors.toMap(ClanSiteLayoutRepository.Summary::guildId, Function.identity(), (a, b) -> a));
        Set<Long> ids = new TreeSet<>(byProfile.keySet());
        ids.addAll(bySite.keySet());
        ids.addAll(byLayout.keySet());
        Set<Long> inBeta = beta.all().stream().map(BetaGuildRepository.BetaGuild::guildId).collect(Collectors.toSet());
        DataArray list = DataArray.empty();
        for (long id : ids) list.add(json(guild, id, byProfile.get(id), bySite.get(id), byLayout.get(id), inBeta.contains(id) || siteService.inBeta(id)));
        DataArray servers = DataArray.empty();
        for (Guild g : guild.getJDA().getGuilds()) {
            servers.add(DataObject.empty().put("guildId", g.getId()).put("name", g.getName()).put("hasSite", ids.contains(g.getIdLong())));
        }
        return DataObject.empty().put("sites", list).put("servers", servers);
    }

    /** One server as the list shows it (after a write too, when there may be nothing left of its site). */
    DataObject one(Guild guild, long id) {
        ClanProfile profile = profiles.get(id).orElse(null);
        ClanSite site = sites.byGuild(id).orElse(null);
        ClanSiteLayoutRepository.Summary layout = layouts.summaries().stream().filter(s -> s.guildId() == id).findFirst().orElse(null);
        return json(guild, id, profile, site, layout, siteService.inBeta(id));
    }

    private DataObject json(Guild home, long id, ClanProfile profile, ClanSite site, ClanSiteLayoutRepository.Summary layout, boolean inBeta) {
        Guild known = home.getJDA().getGuildById(id);
        Long claimedBy = siteRoles.settings(id).claimedBy();
        DataObject out = DataObject.empty().put("guildId", Long.toString(id)).put("serverName", known == null ? null : known.getName())
                .put("botPresent", known != null).put("botless", known == null && siteService.botless(id))
                .put("serverOwnerId", known == null ? null : known.getOwnerId())
                .put("siteOwnerId", claimedBy == null ? null : claimedBy.toString()).put("inBeta", inBeta)
                .put("slug", profile == null ? null : profile.slug()).put("displayName", profile == null ? null : profile.displayName())
                .put("visibility", profile == null ? null : profile.visibility().name()).put("suspended", profile != null && profile.suspended());
        out.put("site", site == null ? null : DataObject.empty().put("enabled", site.enabled()).put("published", site.published())
                .put("live", site.live()).put("takedownReason", site.takedownReason()).put("publishedAt", iso(site.publishedAt()))
                .put("updatedAt", iso(site.updatedAt())));
        out.put("layout", layout == null ? null : DataObject.empty().put("draftRev", layout.draftRev()).put("publishedRev", layout.publishedRev())
                .put("hasPublished", layout.hasPublished()).put("updatedAt", iso(layout.updatedAt())).put("publishedAt", iso(layout.publishedAt())));
        return out;
    }

    private static String iso(Instant at) {
        return at == null ? null : at.toString();
    }

    // ---------- writes ----------

    DataObject address(Guild guild, long actor, long id, DataObject body) {
        ClanProfile current = profiles.get(id).orElseThrow(() -> new ApiError(404, Text.of(guild, "dashboard.that_server_has_no_clan_profile")));
        String slug = current.slug();
        String name = current.displayName();
        List<String> problems = new java.util.ArrayList<>();
        if (body.hasKey("slug") && !body.isNull("slug") && !body.getString("slug", "").isBlank()) {
            slug = body.getString("slug").strip().toLowerCase(Locale.ROOT);
            if (!slug.equals(current.slug())) {
                String problem = ProfileRules.slugProblem(slug);
                if (problem != null) problems.add(problem);
                else if (profiles.slugTaken(slug, id))
                    problems.add("Another clan uses the address \"" + slug + "\", or gave it up less than " + ProfileRules.SLUG_GRACE.toDays() + " days ago. Choose another.");
            }
        }
        if (body.hasKey("displayName") && !body.isNull("displayName")) {
            name = body.getString("displayName", "").strip();
            problems.addAll(ProfileRules.fieldProblems(name, current.tagline(), current.description(), current.requirements(), current.logoUrl(),
                    current.applyUrl(), current.colour(), current.timezone()));
        }
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.the_website_can_t_be_saved"), problems.stream().distinct().toList());
        profiles.saveContent(withAddress(current, slug, name));
        log.info("Owner console: {} set guild {} to address {} ({})", actor, id, slug, name);
        return one(guild, id);
    }

    static ClanProfile withAddress(ClanProfile p, String slug, String name) {
        return new ClanProfile(p.guildId(), p.visibility(), slug, name, p.tagline(), p.description(), p.logoUrl(), p.colour(), p.timezone(), p.focus(),
                p.recruiting(), p.requirements(), p.applyUrl(), p.leaderboardOptIn(), p.verifiedClanName(), p.verifiedAt(), p.verifiedBy(), p.suspendedAt(),
                p.suspendedReason(), p.updatedAt());
    }

    DataObject owner(Guild guild, long actor, long id, DataObject body) {
        String raw = body.isNull("userId") ? null : body.getString("userId", null);
        Long to = null;
        if (raw != null && !raw.isBlank()) {
            long userId = parseId(raw);
            Guild target = guild.getJDA().getGuildById(id);
            if (target == null) throw new ApiError(404, Text.of(guild, "dashboard.the_bot_isn_t_in_that_server"));
            Member member = userId == 0 ? null : DiscordLookups.memberOrSlow(target, userId);
            if (member == null || member.getUser().isBot())
                throw new ApiError(400, Text.of(guild, "dashboard.the_website_can_t_be_saved"), List.of("Pick someone in that server (a Discord user ID)."));
            to = userId;
        }
        siteRoles.handOver(id, to, actor);
        log.info("Owner console: {} handed the site of guild {} to {}", actor, id, to == null ? "the server owner" : to);
        return one(guild, id);
    }

    /** Off ({@code reason} given, or the default) or on again ({@code reason} null), through the takedown, so the clan sees why. */
    DataObject setDisabled(Guild guild, long actor, long id, String reason) {
        ClanSite current = sites.byGuild(id).orElseThrow(() -> new ApiError(404, Text.of(guild, "dashboard.the_website_can_t_be_saved"),
                List.of("That server has no website yet.")));
        String clean = reason == null ? null : (reason.isBlank() ? DEFAULT_REASON : reason.strip());
        if (clean != null && clean.length() > MAX_REASON) throw new ApiError(400, Text.of(guild, "dashboard.give_a_reason_in_at_most", MAX_REASON));
        // off: not published while taken down; on again: live again if it was ever published and is switched on (the draft is untouched)
        boolean published = clean == null ? current.published() || (current.enabled() && current.publishedAt() != null) : false;
        sites.save(new ClanSite(id, current.enabled(), published, current.template(), current.theme(), current.pages(), actor, Instant.now(),
                current.publishedAt(), clean));
        log.info("Owner console: {} switched the site of guild {} {}", actor, id, clean == null ? "on" : "off (" + clean + ")");
        return one(guild, id);
    }

    DataObject delete(Guild guild, long actor, long id, String confirm) {
        String slug = profiles.get(id).map(ClanProfile::slug).orElse(null);
        String expected = slug == null || slug.isBlank() ? "delete" : slug;
        if (!expected.equals(confirm == null ? "" : confirm.strip().toLowerCase(Locale.ROOT)))
            throw new ApiError(400, Text.of(guild, "dashboard.the_website_can_t_be_saved"), List.of("Type " + expected + " to confirm."));
        layouts.delete(id);
        sites.delete(id);
        siteRoles.delete(id);
        log.info("Owner console: {} deleted the website of guild {}", actor, id);
        return list(guild);
    }

    DataObject resetDraft(Guild guild, long actor, long id) {
        var found = layouts.byGuild(id).orElseThrow(() -> new ApiError(404, Text.of(guild, "dashboard.the_website_can_t_be_saved"),
                List.of("That server has no draft.")));
        if (found.published() != null) {
            if (layouts.saveDraft(id, found.published(), found.draftRev(), actor) < 0)
                throw new ApiError(409, Text.of(guild, "dashboard.the_website_can_t_be_saved"), List.of("Someone saved the draft just now. Try again."));
        } else {
            // never published: no draft at all, so the editor starts the guided setup again
            layouts.delete(id);
        }
        log.info("Owner console: {} reset the draft of guild {}", actor, id);
        return one(guild, id);
    }

    DataObject testSite(Guild guild, long actor, DataObject body) {
        long id = parseId(body.getString("guildId", ""));
        Guild target = id == 0 ? null : guild.getJDA().getGuildById(id);
        if (target == null) throw new ApiError(404, Text.of(guild, "dashboard.the_bot_isn_t_in_that_server"));
        if (profiles.get(id).map(ClanProfile::slug).filter(s -> !s.isBlank()).isEmpty()) {
            String name = target.getName().strip();
            if (name.length() > ProfileRules.MAX_NAME) name = name.substring(0, ProfileRules.MAX_NAME).strip();
            profiles.saveContent(new ClanProfile(id, ClanProfile.Visibility.UNLISTED, freeSlug(id), name.isEmpty() ? "Test clan" : name, "", "", null, null,
                    null, EnumSet.noneOf(ClanProfile.Focus.class), ClanProfile.Recruiting.CLOSED, "", null, false, null, null, null, null, null, null));
        }
        ClanSite current = sites.byGuild(id).orElse(ClanSite.empty(id));
        if (!current.enabled()) {
            sites.save(new ClanSite(id, true, current.published(), current.template(), current.theme(), current.pages(), actor, Instant.now(),
                    current.publishedAt(), current.takedownReason()));
        }
        if (!siteService.inBeta(id)) beta.upsert(id, "Test site", AccessLevel.ADMINISTRATOR, actor);
        log.info("Owner console: {} set up a test site for guild {}", actor, id);
        return one(guild, id);
    }

    /** {@code test-<last four digits>}, then -2, -3 and so on until no other clan holds it. */
    private String freeSlug(long id) {
        String digits = Long.toString(id);
        String base = "test-" + digits.substring(Math.max(0, digits.length() - 4));
        String slug = base;
        for (int i = 2; profiles.slugTaken(slug, id) || ProfileRules.slugProblem(slug) != null; i++) slug = base + "-" + i;
        return slug;
    }

    private void requireOwner(Guild guild, Member actor) {
        if (!owners.isOwner(guild.getJDA(), actor.getIdLong())) throw new ApiError(403, Text.of(guild, "dashboard.only_the_bot_owner_can_manage_sites"));
    }

    static long parseId(String raw) {
        try {
            return raw != null && raw.matches("[0-9]{15,20}") ? Long.parseLong(raw) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
