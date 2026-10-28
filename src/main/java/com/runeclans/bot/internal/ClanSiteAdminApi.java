package com.runeclans.bot.internal;

import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.clansite.ClanSite;
import com.runeclans.bot.clansite.ClanSiteBlock;
import com.runeclans.bot.clansite.ClanSiteLayoutRepository;
import com.runeclans.bot.clansite.ClanSiteLayoutRules;
import com.runeclans.bot.clansite.ClanSiteLayoutRulesV2;
import com.runeclans.bot.clansite.ClanSiteRepository;
import com.runeclans.bot.clansite.ClanSiteRules;
import com.runeclans.bot.clansite.ClanSiteService;
import com.runeclans.bot.clansite.SiteRoleGuard;
import com.runeclans.bot.clansite.SiteRoles;
import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.premium.Premium;
import com.runeclans.bot.premium.PremiumFeatures;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import com.runeclans.bot.config.BotBrand;

/**
 * A server's clan website (website builder) for the dashboard's Clan website page (docs/DOMAINS-AND-SITE-BUILDER.md B5, website repo):
 * <ul>
 *   <li>{@code GET clan-site}: the site, its blocks, whether the server is in the builder's beta and whether its clan profile can carry a site</li>
 *   <li>{@code PUT clan-site} {@code {enabled, template, theme, pages}}: checked whole before anything is stored; switching off unpublishes</li>
 *   <li>{@code PUT clan-site/blocks/{page}} {@code {blocks}}: one page's content blocks, in order</li>
 *   <li>{@code POST clan-site/publish}, {@code POST clan-site/unpublish}</li>
 *   <li>{@code GET clan-site/export}: everything the clan wrote, for "Download my site"</li>
 *   <li>{@code POST clan-site/takedown} {@code {reason}} and {@code POST clan-site/restore}: the bot's owner only (a report was upheld)</li>
 *   <li>builder v2 (T-301): {@code GET clan-site/layout}, {@code PUT clan-site/layout} {@code {layout, baseRev}} (the draft, shape checked by
 *   {@link ClanSiteLayoutRules}), {@code POST clan-site/layout/publish} (the draft becomes the public layout and the site is published), and
 *   {@code POST clan-site/delete} {@code {confirm}} (the whole website, after typing its address)</li>
 *   <li>builder v3 (T-363): the layout routes also take layout v2 (checked by {@code ClanSiteLayoutRulesV2} with this server's plan limits);
 *   {@code POST clan-site/layout/publish} takes an optional {@code {label, baseRev}} and records a version (409 when the draft moved past
 *   {@code baseRev}); {@code GET clan-site/layout/versions},
 *   {@code GET clan-site/layout/versions/{id}} (with its layout) and {@code POST clan-site/layout/versions/{id}/restore} {@code {baseRev?}}
 *   (copies it into the draft; never publishes)</li>
 *   <li>connections (N7): {@code GET|PUT clan-site/connections} and {@code POST clan-site/connections/connect|disconnect}, the site
 *   admin's Discord server, data provider and Discord features ({@link SiteDiscordService}); {@code GET|PUT clan-site/connections/invite}, the
 *   server's Discord invite ({@link SiteInviteRoutes})</li>
 *   <li>members ({@link SiteMembersAdmin}): {@code GET|PUT clan-site/member-privacy} (T-395) and {@code GET clan-site/roles/people?q=&role=&page=}
 *   (T-396), admin; for a GET, {@code body} holds the query string's parameters</li>
 *   <li>Clan health (T-593, {@link SiteCockpitAdmin}): {@code GET clan-site/cockpit} and {@code POST clan-site/cockpit/marks} (admin),
 *   {@code PUT clan-site/cockpit/settings} (owner)</li>
 *   <li>Seasons (T-596, {@link SiteSeasons}): {@code GET clan-site/seasons} (admin), {@code PUT clan-site/seasons/settings} (owner)</li>
 * </ul>
 * Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard here and audit-logs every write.
 */
@BService
public class ClanSiteAdminApi implements AdminRouteExtension {
    static final String ROOT = "clan-site";
    static final int MAX_REASON = 300;

    private final ClanSiteRepository repository;
    private final ClanSiteLayoutRepository layouts;
    private final ClanSiteService sites;
    private final ClanProfileService profiles;
    private final BotOwners owners;
    private final Premium premium;
    private final SiteRoleGuard siteRoles;
    private final SiteDiscordService discord;
    /** Layout saves and restores per server (on top of the per-person admin limit): the editor autosaves, so a burst of 3 and then one a second. */
    private final RateLimiter layoutSaves = new RateLimiter(3, 1_000);
    static final int MAX_ROLES_PER_LIST = 20;
    /** What a bot-less site says about Discord roles (editor, member and staff roles): the bot would have to be in the server to see them. */
    static final String DISCORD_ROLES_UNAVAILABLE = "Discord roles are unavailable without the bot: add RuneClan Bot to the server to pick roles, or name editors by Discord user ID.";

    private final SiteAddressRoutes address;
    private final SiteMemberOptionsAdminApi memberOptions;
    private final SiteMembersAdmin members;
    private final SiteInviteRoutes invite;

    public ClanSiteAdminApi(ClanSiteRepository repository, ClanSiteLayoutRepository layouts, ClanSiteService sites, ClanProfileService profiles, BotOwners owners,
                            Premium premium, SiteRoleGuard siteRoles, SiteDiscordService discord, SiteAddressRoutes address,
                            SiteMemberOptionsAdminApi memberOptions, SiteMembersAdmin members, SiteInviteRoutes invite) {
        this.invite = invite;
        this.address = address;
        this.memberOptions = memberOptions;
        this.members = members;
        this.repository = repository;
        this.layouts = layouts;
        this.sites = sites;
        this.profiles = profiles;
        this.owners = owners;
        this.premium = premium;
        this.siteRoles = siteRoles;
        this.discord = discord;
    }

    /**
     * The site role each route needs (T-368, docs/BUILDER-V3.md 8.2), or null for one the bot's owner decides (takedown, restore) or that
     * doesn't exist. Reading and saving the draft: editor. Publishing, unpublishing, restoring a version: admin, or editor with "Editors may
     * publish" on. The site's settings, its roles, the v1 blocks and the export: admin. Deleting and handing the site over: owner.
     */
    static SiteRoles.Action actionFor(String method, String[] parts) {
        if (parts.length < 1 || !parts[0].equals("clan-site")) return null;
        String sub = parts.length > 1 ? parts[1] : "";
        boolean get = method.equals("GET");
        return switch (sub) {
            case "" -> get ? SiteRoles.Action.EDIT : SiteRoles.Action.SETTINGS;
            case "layout" -> {
                if (parts.length == 2) yield SiteRoles.Action.EDIT;
                if (parts.length == 3 && parts[2].equals("publish")) yield SiteRoles.Action.PUBLISH;
                if (parts[2].equals("versions")) yield parts.length == 5 && parts[4].equals("restore") ? SiteRoles.Action.PUBLISH : SiteRoles.Action.EDIT;
                yield SiteRoles.Action.EDIT;
            }
            case "publish", "unpublish" -> SiteRoles.Action.PUBLISH;
            case "roles" -> parts.length == 3 && (parts[2].equals("hand-over") || (parts[2].equals("admin-access") && !get))
                    ? SiteRoles.Action.OWN : SiteRoles.Action.SETTINGS;
            case "delete" -> SiteRoles.Action.OWN;
            case SiteCockpitAdmin.PATH -> SiteCockpitAdmin.actionFor(method, parts);
            case SiteSeasons.PATH -> SiteSeasons.actionFor(method, parts);
            case "takedown", "restore" -> null;
            default -> SiteRoles.Action.SETTINGS;
        };
    }

    /** Editors without dashboard access may use the editor's own routes (reading the site and its layout, saving, versions, and publishing). */
    @Override
    public boolean admits(Guild guild, Member actor, String method, String[] parts) {
        return admits(SiteCaller.of(guild, actor), method, parts);
    }

    boolean admits(SiteCaller c, String method, String[] parts) {
        if (actionFor(method, parts) == null) return false;
        boolean editorPath = parts.length == 1 ? method.equals("GET") : parts[1].equals("layout");
        return editorPath && c.may(siteRoles, SiteRoles.Action.EDIT);
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        return route(SiteCaller.of(guild, actor), method, parts, body);
    }

    /** {@link #route(Guild, Member, String, String[], Supplier)} for any caller, a bot-less site's included ({@link BotlessAdminApi}). */
    DataObject route(SiteCaller c, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("clan-site")) return null;
        SiteRoles.Action action = actionFor(method, parts);
        if (action != null && !c.may(siteRoles, action)) throw new ApiError(403, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), List.of(refusal(action)));
        if (parts.length == 3 && parts[1].equals("roles") && parts[2].equals("people")) {
            AdminRouteExtension.requireMethod(method, "GET");
            return members.people(c, body.get());
        }
        if (parts.length >= 2 && parts[1].equals("roles")) return roles(c, method, parts, body);
        if (parts.length == 2 && parts[1].equals("member-privacy")) return members.privacy(c, method, body);
        if (parts.length >= 2 && parts[1].equals(SiteCockpitAdmin.PATH)) return members.cockpit(c, method, parts, body);
        if (parts.length >= 2 && parts[1].equals(SiteSeasons.PATH)) return members.seasons(c, method, parts, body);
        if (parts.length >= 2 && parts[1].equals("connections")) return connections(c, method, parts, body);
        if (parts.length >= 2 && parts.length <= 3 && parts[1].equals("address")) return address.route(c.guildId(), method, parts.length == 3 ? parts[2] : "", body);
        // the site admin's own way to the member options (site admins who aren't dashboard admins included): needs the bot in the server
        if (parts.length == 2 && parts[1].equals("member-options")) {
            if (c.botless()) throw new ApiError(409, "Members change their nickname and name colour through " + BotBrand.NAME + ", and it isn't in this server.");
            return memberOptions.route(c.guild(), method, body);
        }
        if (parts.length == 1) {
            if (method.equals("GET")) return get(c);
            AdminRouteExtension.requireMethod(method, "PUT");
            return save(c, body.get());
        }
        if (parts.length == 3 && parts[1].equals("blocks")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            return saveBlocks(c, parts[2], body.get());
        }
        if (parts.length == 3 && parts[1].equals("layout") && parts[2].equals("publish")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return publishLayout(c, body.get());
        }
        if (parts.length >= 3 && parts[1].equals("layout") && parts[2].equals("versions")) {
            if (parts.length == 3) {
                AdminRouteExtension.requireMethod(method, "GET");
                return versions(c);
            }
            long versionId = versionId(c, parts[3]);
            if (parts.length == 4) {
                AdminRouteExtension.requireMethod(method, "GET");
                return version(c, versionId);
            }
            if (parts.length == 5 && parts[4].equals("restore")) {
                AdminRouteExtension.requireMethod(method, "POST");
                return restoreVersion(c, versionId, body.get());
            }
            return null;
        }
        if (parts.length != 2) return null;
        return switch (parts[1]) {
            case "layout" -> {
                if (method.equals("GET")) yield botPresence(c, layout(c).put("you", youJson(c)));
                AdminRouteExtension.requireMethod(method, "PUT");
                yield saveLayout(c, body.get());
            }
            case "delete" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield delete(c, body.get().getString("confirm", ""));
            }
            case "export" -> {
                AdminRouteExtension.requireMethod(method, "GET");
                yield export(c);
            }
            case "publish" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield publish(c, true);
            }
            case "unpublish" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield publish(c, false);
            }
            case "takedown" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield takedown(c, body.get().getString("reason", ""));
            }
            case "restore" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield takedown(c, null);
            }
            default -> null;
        };
    }

    // ---------- read ----------

    DataObject get(SiteCaller c) {
        long id = c.guildId();
        ClanSite site = sites.of(id);
        ClanProfile profile = profiles.profileOf(id);
        DataArray blocks = DataArray.empty();
        for (ClanSiteBlock b : repository.blocks(id)) blocks.add(blockJson(b));
        return DataObject.empty()
                .put("inBeta", sites.inBeta(id))
                .put("isOwner", owners.isOwner(c.jda(), c.userId()))
                .put("slug", profile.slug())
                .put("profileProblem", profileProblem(id, profile))
                .put("site", siteJson(site))
                .put("blocks", blocks)
                .put("you", youJson(c))
                .put("limits", DataObject.empty().put("title", ClanSiteRules.MAX_TITLE).put("blocksPerPage", ClanSiteRules.MAX_BLOCKS_PER_PAGE)
                        .put("markdown", ClanSiteRules.MAX_MARKDOWN).put("links", ClanSiteRules.MAX_LINKS).put("url", ClanSiteRules.MAX_URL));
    }

    /** Why the clan profile can't carry a site yet, or null: a site lives at the profile's address and follows its visibility. */
    private String profileProblem(long guildId, ClanProfile profile) {
        if (profile.slug() == null || profile.slug().isBlank()) return "Choose your clan's web address on the Clan profile page first.";
        if (profiles.publicView(guildId, true).isEmpty()) return "Your clan profile is private or suspended: set it to Unlisted or Public on the Clan profile page.";
        return null;
    }

    private static DataObject siteJson(ClanSite s) {
        return DataObject.empty().put("enabled", s.enabled()).put("published", s.published()).put("template", s.template().name())
                .put("theme", parse(s.theme(), true)).put("pages", parse(s.pages(), false))
                .put("publishedAt", s.publishedAt() == null ? null : s.publishedAt().toString())
                .put("updatedAt", s.updatedAt() == null ? null : s.updatedAt().toString())
                .put("takedownReason", s.takedownReason());
    }

    private static DataObject blockJson(ClanSiteBlock b) {
        return DataObject.empty().put("page", b.page()).put("position", b.position()).put("kind", b.kind().name()).put("body", parse(b.body(), true));
    }

    private static Object parse(String raw, boolean object) {
        try {
            return object ? DataObject.fromJson(raw == null || raw.isBlank() ? "{}" : raw) : DataArray.fromJson(raw == null || raw.isBlank() ? "[]" : raw);
        } catch (RuntimeException e) {
            return object ? DataObject.empty() : DataArray.empty();
        }
    }

    // ---------- writes ----------

    private void requireBeta(SiteCaller c) {
        if (!sites.inBeta(c.guildId())) throw new ApiError(403, Text.of(c.guildId(), "dashboard.the_clan_website_builder_is_in"));
    }

    DataObject save(SiteCaller c, DataObject body) {
        requireBeta(c);
        List<String> problems = new ArrayList<>();
        ClanSite current = sites.of(c.guildId());
        ClanSite.Template template = ClanSite.Template.parse(body.getString("template", current.template().name())).orElse(null);
        if (template == null) problems.add("Pick one of the three templates.");
        String theme = ClanSiteRules.cleanTheme(body.optObject("theme").orElse(DataObject.empty()), problems);
        String pages = ClanSiteRules.cleanPages(body.optArray("pages").orElse(DataArray.empty()), problems);
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), distinct(problems));
        boolean enabled = body.getBoolean("enabled", current.enabled());
        ClanSite saved = new ClanSite(c.guildId(), enabled, enabled && current.published(), template, theme, pages, c.userId(), Instant.now(),
                current.publishedAt(), current.takedownReason());
        repository.save(saved);
        return get(c);
    }

    DataObject saveBlocks(SiteCaller c, String page, DataObject body) {
        requireBeta(c);
        List<String> problems = new ArrayList<>();
        List<ClanSiteBlock> blocks = ClanSiteRules.cleanBlocks(c.guildId(), page, body.optArray("blocks").orElse(DataArray.empty()), problems);
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(c.guildId(), "dashboard.those_blocks_can_t_be_saved"), distinct(problems));
        repository.replaceBlocks(c.guildId(), page, blocks);
        return get(c);
    }

    DataObject publish(SiteCaller c, boolean publish) {
        ClanSite current = sites.of(c.guildId());
        if (publish) requirePublishable(c, current);
        savePublished(c, current, publish);
        return get(c);
    }

    private void savePublished(SiteCaller c, ClanSite current, boolean publish) {
        repository.save(new ClanSite(current.guildId(), current.enabled(), publish, current.template(), current.theme(), current.pages(), c.userId(),
                Instant.now(), publish ? Instant.now() : current.publishedAt(), current.takedownReason()));
    }

    private void requirePublishable(SiteCaller c, ClanSite current) {
        requireBeta(c);
        List<String> problems = new ArrayList<>();
        if (!current.enabled()) problems.add("Switch the website on first.");
        String profile = profileProblem(c.guildId(), profiles.profileOf(c.guildId()));
        if (profile != null) problems.add(profile);
        if (current.takedownReason() != null && !current.takedownReason().isBlank()) problems.add("This website was taken down by " + BotBrand.NAME + "'s owner: " + current.takedownReason());
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_published"), problems);
    }

    /** The owner takes a site down (with a reason the clan sees) or lets it be published again ({@code reason} null). */
    DataObject takedown(SiteCaller c, String reason) {
        if (!owners.isOwner(c.jda(), c.userId())) throw new ApiError(403, Text.of(c.guildId(), "dashboard.only_jonnybot_owner_can_take_a"));
        String clean = reason == null ? null : reason.trim();
        if (clean != null && (clean.length() < 5 || clean.length() > MAX_REASON)) throw new ApiError(400, Text.of(c.guildId(), "dashboard.give_a_reason_of_5_to", MAX_REASON));
        ClanSite current = sites.of(c.guildId());
        repository.save(new ClanSite(current.guildId(), current.enabled(), clean == null && current.published(), current.template(), current.theme(), current.pages(),
                c.userId(), Instant.now(), current.publishedAt(), clean));
        return get(c);
    }

    // ---------- builder v2 layout (T-301) ----------

    /**
     * The draft and whether a published copy exists, for the editor. The draft is null before the first save. {@code limits} are v1's;
     * {@code limitsV2} are layout v2's (builder v3), with the page and widget counts and kept versions of this server's plan.
     */
    DataObject layout(SiteCaller c) {
        long id = c.guildId();
        var found = layouts.byGuild(id);
        DataObject limits = DataObject.empty().put("bytes", ClanSiteLayoutRules.MAX_BYTES).put("pages", ClanSiteLayoutRules.MAX_PAGES)
                .put("sections", ClanSiteLayoutRules.MAX_SECTIONS).put("columns", ClanSiteLayoutRules.MAX_COLUMNS)
                .put("widgets", ClanSiteLayoutRules.MAX_WIDGETS_PER_COLUMN).put("settingsBytes", ClanSiteLayoutRules.MAX_SETTINGS_BYTES);
        ClanSiteLayoutRulesV2.Limits plan = planLimits(id);
        DataObject limitsV2 = DataObject.empty().put("bytes", ClanSiteLayoutRulesV2.MAX_BYTES).put("pages", plan.pages())
                .put("sections", ClanSiteLayoutRulesV2.MAX_SECTIONS).put("rows", ClanSiteLayoutRulesV2.MAX_ROWS).put("cells", ClanSiteLayoutRulesV2.MAX_CELLS)
                .put("widgetsPerCell", ClanSiteLayoutRulesV2.MAX_WIDGETS_PER_CELL).put("widgetsPerPage", plan.widgetsPerPage())
                .put("settingsBytes", ClanSiteLayoutRulesV2.MAX_SETTINGS_BYTES).put("navItems", ClanSiteLayoutRulesV2.MAX_NAV_ITEMS)
                .put("versions", keptVersions(id));
        DataObject json = DataObject.empty().put("limits", limits).put("limitsV2", limitsV2).put("plan", planJson(id)).put("inBeta", sites.inBeta(id));
        if (found.isEmpty()) return json.putNull("draft").put("draftRev", 0).put("publishedRev", 0).putNull("publishedAt").putNull("updatedAt");
        var l = found.get();
        return json.put("draft", parse(l.draft(), true)).put("draftRev", l.draftRev()).put("publishedRev", l.publishedRev())
                .put("publishedAt", l.publishedAt() == null ? null : l.publishedAt().toString())
                .put("updatedAt", l.updatedAt() == null ? null : l.updatedAt().toString());
    }

    /** {@code {layout, baseRev}}: the draft, checked whole; refused when another save landed since {@code baseRev}. */
    DataObject saveLayout(SiteCaller c, DataObject body) {
        requireBeta(c);
        if (!layoutSaves.tryAcquire(Long.toString(c.guildId()))) throw new ApiError(429, Text.of(c.guildId(), "dashboard.you_re_doing_that_too_fast"));
        List<String> problems = new ArrayList<>();
        String clean;
        try {
            String stored = layouts.byGuild(c.guildId()).map(ClanSiteLayoutRepository.Layouts::draft).orElse(null);
            ClanSiteLayoutRulesV2.Limits limits = planLimits(c.guildId()).keeping(stored);
            clean = ClanSiteLayoutRules.clean(body.optObject("layout").orElse(null), limits, problems);
        } catch (RuntimeException e) {
            problems.add("The layout couldn't be read. Reload the page and try again.");
            clean = null;
        }
        if (clean == null) throw new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), distinct(problems));
        int rev = layouts.saveDraft(c.guildId(), clean, Math.max(0, body.getInt("baseRev", 0)), c.userId());
        // whoever starts a site's builder layout claims it (an owner of the site, 8.2), unless someone already has
        if (rev == 1) siteRoles.claim(c.guildId(), c.userId());
        if (rev < 0) {
            throw new ApiError(409, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"),
                    List.of("Someone saved this site from another tab or window. Reload to see the newest version."));
        }
        return layout(c);
    }

    /**
     * Publishes the draft: the same checks as v1's publish (switched on, profile can carry a site, not taken down), then the copy, which is
     * also recorded as a version with the optional {@code label} (up to {@link ClanSiteLayoutRepository#MAX_VERSION_LABEL} characters). With
     * {@code baseRev} (the editor's draft revision) it is refused with 409 when another save landed since, checked in the same update as the copy.
     */
    DataObject publishLayout(SiteCaller c, DataObject body) {
        if (layouts.byGuild(c.guildId()).isEmpty()) {
            throw new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_published"), List.of("Save the site before publishing it."));
        }
        String label = body.isNull("label") ? null : body.getString("label", null);
        if (label != null && label.strip().length() > ClanSiteLayoutRepository.MAX_VERSION_LABEL) {
            throw new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_published"),
                    List.of("Keep the version name to " + ClanSiteLayoutRepository.MAX_VERSION_LABEL + " characters."));
        }
        requirePublishable(c, sites.of(c.guildId()));
        if (layouts.publishDraft(c.guildId(), c.userId(), label, Math.max(0, body.getInt("baseRev", 0))) < 0) {
            throw new ApiError(409, Text.of(c.guildId(), "dashboard.the_website_can_t_be_published"),
                    List.of("Someone saved this site from another tab or window. Reload to see the newest version."));
        }
        // checked above, before the copy: only the save is left, so nothing after the copy can refuse a publish that already went live
        savePublished(c, sites.of(c.guildId()), true);
        return layout(c);
    }

    // ---------- versions (T-363, docs/BUILDER-V3.md 6.5) ----------

    /** The published versions this server's plan lets it see, newest first ({@code current}: the one the public site shows). */
    DataObject versions(SiteCaller c) {
        long id = c.guildId();
        int published = layouts.byGuild(id).map(ClanSiteLayoutRepository.Layouts::publishedRev).orElse(0);
        DataArray list = DataArray.empty();
        boolean currentSeen = false;
        for (ClanSiteLayoutRepository.Version v : layouts.versions(id, keptVersions(id))) {
            boolean current = !currentSeen && v.rev() == published;
            currentSeen |= current;
            list.add(versionJson(v).put("current", current));
        }
        return DataObject.empty().put("versions", list).put("kept", keptVersions(id)).put("plan", planJson(id));
    }

    /** One version with its layout, so the history can show or compare it. */
    DataObject version(SiteCaller c, long versionId) {
        long id = c.guildId();
        var found = layouts.version(id, versionId, keptVersions(id))
                .orElseThrow(() -> new ApiError(404, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), List.of("That version doesn't exist any more.")));
        return versionJson(found).put("layout", parse(found.layout(), true));
    }

    /** {@code {baseRev?}}: copies a version into the draft as a new revision; it goes public only when published again. */
    DataObject restoreVersion(SiteCaller c, long versionId, DataObject body) {
        requireBeta(c);
        if (!layoutSaves.tryAcquire(Long.toString(c.guildId()))) throw new ApiError(429, Text.of(c.guildId(), "dashboard.you_re_doing_that_too_fast"));
        long id = c.guildId();
        if (layouts.version(id, versionId, keptVersions(id)).isEmpty()) {
            throw new ApiError(404, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), List.of("That version doesn't exist any more."));
        }
        int rev = layouts.restoreVersion(id, versionId, Math.max(0, body.getInt("baseRev", 0)), c.userId());
        if (rev < 0) {
            throw new ApiError(409, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"),
                    List.of("Someone saved this site from another tab or window. Reload to see the newest version."));
        }
        return layout(c);
    }

    private static long versionId(SiteCaller c, String raw) {
        try {
            long id = Long.parseLong(raw);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // falls through to the 404
        }
        throw new ApiError(404, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), List.of("That version doesn't exist any more."));
    }

    private static DataObject versionJson(ClanSiteLayoutRepository.Version v) {
        return DataObject.empty().put("id", v.id()).put("rev", v.rev()).put("label", v.label())
                .put("publishedBy", v.publishedBy() == null ? null : String.valueOf(v.publishedBy()))
                .put("publishedAt", v.publishedAt() == null ? null : v.publishedAt().toString());
    }

    // ---------- plan (T-366: the full plan, tier, until, graceUntil, source, from Billing) ----------

    private ClanSiteLayoutRulesV2.Limits planLimits(long guildId) {
        return ClanSiteLayoutRulesV2.Limits.of(premium.limit(guildId, PremiumFeatures.SITE_PAGES).orElse((long) ClanSiteLayoutRulesV2.MAX_PAGES),
                premium.limit(guildId, PremiumFeatures.SITE_WIDGETS_PER_PAGE).orElse((long) ClanSiteLayoutRulesV2.MAX_WIDGETS_PER_PAGE));
    }

    private int keptVersions(long guildId) {
        long kept = premium.limit(guildId, PremiumFeatures.SITE_VERSIONS).orElse((long) ClanSiteLayoutRepository.KEPT_VERSIONS);
        return (int) Math.max(1, Math.min(ClanSiteLayoutRepository.KEPT_VERSIONS, kept));
    }

    /**
     * The site's plan in the layout answers ({@code {tier, until, graceUntil, source}}, tier "free" without one). Counted as present: either the
     * bot is in this server, or it is a bot-less site, which never starts the leave grace.
     */
    DataObject planJson(long guildId) {
        return com.runeclans.bot.premium.Billing.planJson(guildId, true);
    }

    /**
     * Deletes the website (its v1 settings and blocks and its v2 layouts). {@code confirm} must be the site's address (or "delete" when it has
     * none), so a stray click can't do it. A site the bot's owner took down can't be deleted to clear the takedown.
     */
    DataObject delete(SiteCaller c, String confirm) {
        long id = c.guildId();
        ClanSite current = sites.of(id);
        if (current.takedownReason() != null && !current.takedownReason().isBlank()) {
            throw new ApiError(403, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), List.of("This website was taken down by " + BotBrand.NAME + "'s owner, so it can't be deleted."));
        }
        String slug = profiles.profileOf(id).slug();
        String expected = slug == null || slug.isBlank() ? "delete" : slug;
        if (!expected.equals(confirm == null ? "" : confirm.strip().toLowerCase(java.util.Locale.ROOT))) {
            throw new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), List.of("Type " + expected + " to confirm."));
        }
        layouts.delete(id);
        repository.delete(id);
        siteRoles.delete(id);
        members.forget(id);
        return get(c);
    }

    // ---------- connections (N7, the site admin's Connections page) ----------

    /**
     * {@code GET|PUT clan-site/connections}, {@code POST clan-site/connections/connect} {@code {guildId}} and {@code POST
     * clan-site/connections/disconnect}: the site's Discord server, its data provider and its Discord features ({@link SiteDiscordService}).
     * {@code GET|PUT clan-site/connections/invite}: the server's Discord invite ({@link SiteInviteRoutes}, T-633). Admin, like the site's other
     * settings.
     */
    DataObject connections(SiteCaller c, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length == 3 && parts[2].equals("invite")) return invite.route(c.guildId(), method, body);
        if (parts.length == 2) {
            if (method.equals("GET")) return botPresence(c, discord.connectionState(c));
            AdminRouteExtension.requireMethod(method, "PUT");
            return botPresence(c, discord.saveConnection(c, body.get()));
        }
        if (parts.length == 3 && (parts[2].equals("connect") || parts[2].equals("disconnect"))) {
            AdminRouteExtension.requireMethod(method, "POST");
            return botPresence(c, discord.connect(c, parts[2].equals("connect"), body.get()));
        }
        return null;
    }

    // ---------- site roles (T-368, docs/BUILDER-V3.md 8.2) ----------

    private static String refusal(SiteRoles.Action action) {
        return switch (action) {
            case EDIT -> "Only the site's editors can do that.";
            case PUBLISH -> "Only the site's admins can publish or go back to an old version (an admin can let editors publish too).";
            case SETTINGS -> "Only the site's admins can change that.";
            case OWN -> "Only the site's owner (the server owner, or whoever set the site up) can do that.";
        };
    }

    /**
     * {@code GET clan-site/roles}: the site's role settings, the clan Member role used when no member roles are set, and the asker's own role.
     * {@code PUT clan-site/roles} {@code {editorRoleIds, memberRoleIds, staffRoleIds, editorsMayPublish}}: admin. {@code POST
     * clan-site/roles/hand-over} {@code {userId}} (null: back to the server owner alone): owner.
     */
    DataObject roles(SiteCaller c, String method, String[] parts, Supplier<DataObject> body) {
        long id = c.guildId();
        if (parts.length == 3 && parts[2].equals("hand-over")) {
            AdminRouteExtension.requireMethod(method, "POST");
            DataObject b = body.get();
            String raw = b.isNull("userId") ? null : b.getString("userId", null);
            Long to = null;
            if (raw != null) {
                long userId = parseId(raw);
                if (c.botless()) {
                    // the bot can't see who is in a server it isn't in: any Discord user id will do
                    if (userId == 0) throw new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), List.of("Give a Discord user ID (17 to 20 digits)."));
                } else {
                    Member target = userId == 0 ? null : DiscordLookups.memberOrSlow(c.guild(), userId);
                    if (target == null || target.getUser().isBot()) throw new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), List.of("Pick someone in this server."));
                }
                to = userId;
            }
            siteRoles.handOver(id, to, c.userId());
            return rolesJson(c);
        }
        if (parts.length == 3 && parts[2].equals("admin-access")) {
            if (method.equals("GET")) return rolesJson(c);
            AdminRouteExtension.requireMethod(method, "PUT");
            siteRoles.saveAdminAccess(id, adminAccess(c, body.get()), c.userId());
            return rolesJson(c);
        }
        if (parts.length != 2) return null;
        if (method.equals("GET")) return rolesJson(c);
        AdminRouteExtension.requireMethod(method, "PUT");
        DataObject b = body.get();
        List<String> problems = new ArrayList<>();
        List<Long> editors = roleIds(c, b, "editorRoleIds", false, problems);
        List<Long> members = roleIds(c, b, "memberRoleIds", true, problems);
        List<Long> staff = roleIds(c, b, "staffRoleIds", false, problems);
        SiteRoles.Settings current = siteRoles.settings(id);
        // editors by Discord user id: kept as they are unless the save names them (a page that doesn't know them yet never clears them)
        List<Long> editorUsers = b.hasKey("editorUserIds") ? userIds(b, "editorUserIds", problems) : current.editorUserIds();
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), distinct(problems));
        siteRoles.save(id, new SiteRoles.Settings(editors, members, staff, b.getBoolean("editorsMayPublish", false), current.claimedBy(), editorUsers), c.userId());
        return rolesJson(c);
    }

    /**
     * The Discord permissions the staff-server rule may ask for (N5), by the website's key: a member of the staff server holding it (or
     * Administrator, or owning that server) may open the site admin.
     */
    static final java.util.Map<String, Long> STAFF_PERMISSIONS = java.util.Map.of(
            "administrator", 0x8L, "manage_server", 0x20L, "manage_roles", 0x10000000L, "manage_channels", 0x10L,
            "ban_members", 0x4L, "kick_members", 0x2L, "manage_messages", 0x2000L, "moderate_members", 0x10000000000L);

    /**
     * {@code PUT clan-site/roles/admin-access} {@code {userIds, roleIds, staffServerId, staffPermission}}: who else may open this site's admin
     * panel, checked whole. The site's owner (and overall admins) only. Each list at most {@link #MAX_ROLES_PER_LIST}; roles must exist in the
     * server (none on a bot-less site, which can't see roles); {@code staffServerId} null or a server id, {@code staffPermission} a key of
     * {@link #STAFF_PERMISSIONS}. A key left out keeps what is stored.
     */
    private SiteRoles.AdminAccess adminAccess(SiteCaller c, DataObject b) {
        SiteRoles.AdminAccess current = siteRoles.settings(c.guildId()).adminAccess();
        List<String> problems = new ArrayList<>();
        List<Long> users = b.hasKey("userIds") ? userIds(b, "userIds", problems) : current.userIds();
        List<Long> roles = b.hasKey("roleIds") ? roleIds(c, b, "roleIds", false, problems) : current.roleIds();
        Long server = current.staffServerId();
        long permission = current.staffPermission();
        if (b.hasKey("staffServerId")) {
            String raw = b.isNull("staffServerId") ? "" : b.getString("staffServerId", "").strip();
            if (raw.isEmpty()) server = null;
            else if (parseId(raw) == 0) problems.add("The staff server must be a Discord server ID (17 to 20 digits).");
            else server = parseId(raw);
        }
        if (b.hasKey("staffPermission")) {
            Long bits = b.isNull("staffPermission") ? null : STAFF_PERMISSIONS.get(b.getString("staffPermission", ""));
            if (bits == null && server != null) problems.add("Pick the permission staff need in that server.");
            permission = bits == null ? 0 : bits;
        }
        if (server != null && permission == 0) problems.add("Pick the permission staff need in that server.");
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), distinct(problems));
        return new SiteRoles.AdminAccess(users, roles, server, permission);
    }

    private static String permissionKey(long bits) {
        for (var e : STAFF_PERMISSIONS.entrySet()) if (e.getValue() == bits) return e.getKey();
        return null;
    }

    /**
     * A list of role ids of this server: at most {@link #MAX_ROLES_PER_LIST}, each a role that exists; @everyone only as a member role. A bot-less
     * site can't see the server's roles, so it takes none.
     */
    private static List<Long> roleIds(SiteCaller c, DataObject body, String key, boolean everyoneAllowed, List<String> problems) {
        DataArray raw = body.optArray(key).orElse(DataArray.empty());
        if (c.botless()) {
            if (raw.length() > 0) problems.add(DISCORD_ROLES_UNAVAILABLE);
            return List.of();
        }
        Guild guild = c.guild();
        if (raw.length() > MAX_ROLES_PER_LIST) {
            problems.add("Pick up to " + MAX_ROLES_PER_LIST + " roles in each list.");
            return List.of();
        }
        Set<Long> out = new LinkedHashSet<>();
        for (int i = 0; i < raw.length(); i++) {
            long roleId = parseId(raw.getString(i, ""));
            if (roleId == 0 || guild.getRoleById(roleId) == null) problems.add("One of the roles doesn't exist any more. Reload the page.");
            else if (roleId == c.guildId() && !everyoneAllowed) problems.add("@everyone can't be an admin, editor or staff role: that would be everyone in the server.");
            else out.add(roleId);
        }
        return List.copyOf(out);
    }

    /** People named by Discord user id (editors, or site admins): at most {@link #MAX_ROLES_PER_LIST}, each 17 to 20 digits. */
    private static List<Long> userIds(DataObject body, String key, List<String> problems) {
        DataArray raw = body.optArray(key).orElse(DataArray.empty());
        if (raw.length() > MAX_ROLES_PER_LIST) {
            problems.add("Name up to " + MAX_ROLES_PER_LIST + " people by their Discord user ID.");
            return List.of();
        }
        Set<Long> out = new LinkedHashSet<>();
        for (int i = 0; i < raw.length(); i++) {
            long userId = parseId(raw.getString(i, ""));
            if (userId == 0) problems.add("A Discord user ID is 17 to 20 digits.");
            else out.add(userId);
        }
        return List.copyOf(out);
    }

    private static long parseId(String raw) {
        try {
            return raw != null && raw.matches("[0-9]{15,20}") ? Long.parseLong(raw) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private DataObject rolesJson(SiteCaller c) {
        SiteRoles.Settings s = siteRoles.settings(c.guildId());
        DataObject discordRoles = DataObject.empty().put("available", !c.botless());
        if (c.botless()) discordRoles.put("reason", DISCORD_ROLES_UNAVAILABLE);
        return botPresence(c, DataObject.empty().put("editorRoleIds", idsJson(s.editorRoleIds())).put("memberRoleIds", idsJson(s.memberRoleIds()))
                .put("staffRoleIds", idsJson(s.staffRoleIds())).put("editorsMayPublish", s.editorsMayPublish())
                .put("editorUserIds", idsJson(s.editorUserIds())).put("discordRoles", discordRoles)
                .put("claimedBy", s.claimedBy() == null ? null : String.valueOf(s.claimedBy()))
                .put("adminAccess", DataObject.empty().put("userIds", idsJson(s.adminAccess().userIds())).put("roleIds", idsJson(s.adminAccess().roleIds()))
                        .put("staffServerId", s.adminAccess().staffServerId() == null ? null : String.valueOf(s.adminAccess().staffServerId()))
                        .put("staffPermission", permissionKey(s.adminAccess().staffPermission())))
                .put("you", youJson(c)));
    }

    /** On a bot-less site, {@code botPresent: false} (the website's name for it) and {@code botless: true}; a bot-present answer is unchanged. */
    private static DataObject botPresence(SiteCaller c, DataObject answer) {
        return c.botless() ? answer.put("botPresent", false).put("botless", true) : answer;
    }

    /**
     * The asker's own role on the site and what it lets them do, so the editor shows only what will work. {@code asRuneClanAdmin}: they own it
     * only as a RuneClan Admin, so the editor and site admin say so and their changes are marked in the site's history (T-445).
     */
    private DataObject youJson(SiteCaller c) {
        SiteRoles.Role role = c.role(siteRoles);
        SiteRoles.Settings s = siteRoles.settings(c.guildId());
        SiteRoles.Facts facts = c.facts() != null ? c.facts() : c.member() != null ? siteRoles.facts(c.guild(), c.userId(), c.member()) : null;
        boolean overall = facts != null ? facts.overallAdmin() : siteRoles.overallAdmin(c.jda(), c.userId());
        return DataObject.empty().put("role", role.key()).put("canPublish", SiteRoles.allows(role, SiteRoles.Action.PUBLISH, s))
                .put("canChangeSettings", SiteRoles.allows(role, SiteRoles.Action.SETTINGS, s)).put("isOwner", role == SiteRoles.Role.OWNER)
                .put("overallAdmin", overall).put("asRuneClanAdmin", SiteRoles.onlyAsOverallAdmin(facts, s));
    }

    /** The site's role settings, for naming a change in its history ({@link TicketAdminApi#siteAuditName}). */
    SiteRoles.Settings siteSettings(long guildId) {
        return siteRoles.settings(guildId);
    }

    private static DataArray idsJson(List<Long> ids) {
        DataArray out = DataArray.empty();
        for (Long id : ids) out.add(String.valueOf(id));
        return out;
    }

    // ---------- export ----------

    /** Everything the clan wrote (look, pages, blocks) and the addresses of the images it used, for "Download my site". */
    DataObject export(SiteCaller c) {
        long id = c.guildId();
        ClanSite site = sites.of(id);
        DataArray blocks = DataArray.empty();
        Set<String> images = new LinkedHashSet<>();
        DataObject theme = (DataObject) parse(site.theme(), true);
        for (String key : List.of("logoUrl", "bannerUrl")) {
            String url = theme.getString(key, null);
            if (url != null) images.add(url);
        }
        for (ClanSiteBlock b : repository.blocks(id)) {
            DataObject json = blockJson(b);
            blocks.add(json);
            if (b.kind() == ClanSiteBlock.Kind.IMAGE) {
                String url = json.getObject("body").getString("url", null);
                if (url != null && !url.isBlank()) images.add(url);
            }
        }
        return DataObject.empty().put("format", "jonnybot-clan-site/1").put("exportedAt", Instant.now().toString()).put("slug", profiles.profileOf(id).slug())
                .put("site", siteJson(site)).put("blocks", blocks).put("images", DataArray.fromCollection(images))
                .put("layout", layouts.byGuild(id).map(l -> parse(l.draft(), true)).orElse(null));
    }

    private static List<String> distinct(List<String> problems) {
        return problems.stream().distinct().toList();
    }
}
