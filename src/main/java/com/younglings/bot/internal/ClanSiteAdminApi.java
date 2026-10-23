package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileService;
import com.younglings.bot.clansite.ClanSite;
import com.younglings.bot.clansite.ClanSiteBlock;
import com.younglings.bot.clansite.ClanSiteLayoutRepository;
import com.younglings.bot.clansite.ClanSiteLayoutRules;
import com.younglings.bot.clansite.ClanSiteLayoutRulesV2;
import com.younglings.bot.clansite.ClanSiteRepository;
import com.younglings.bot.clansite.ClanSiteRules;
import com.younglings.bot.clansite.ClanSiteService;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.notice.BotOwners;
import com.younglings.bot.premium.Premium;
import com.younglings.bot.premium.PremiumFeatures;
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
import com.younglings.bot.config.BotBrand;

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
 *   {@code POST clan-site/layout/publish} takes an optional {@code {label}} and records a version; {@code GET clan-site/layout/versions},
 *   {@code GET clan-site/layout/versions/{id}} (with its layout) and {@code POST clan-site/layout/versions/{id}/restore} {@code {baseRev?}}
 *   (copies it into the draft; never publishes)</li>
 * </ul>
 * Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard here and audit-logs every write.
 */
@BService
public class ClanSiteAdminApi implements AdminRouteExtension {
    static final int MAX_REASON = 300;

    private final ClanSiteRepository repository;
    private final ClanSiteLayoutRepository layouts;
    private final ClanSiteService sites;
    private final ClanProfileService profiles;
    private final BotOwners owners;
    private final Premium premium;
    /** Layout saves and restores per server (on top of the per-person admin limit): the editor autosaves, so a burst of 3 and then one a second. */
    private final RateLimiter layoutSaves = new RateLimiter(3, 1_000);

    public ClanSiteAdminApi(ClanSiteRepository repository, ClanSiteLayoutRepository layouts, ClanSiteService sites, ClanProfileService profiles, BotOwners owners,
                            Premium premium) {
        this.repository = repository;
        this.layouts = layouts;
        this.sites = sites;
        this.profiles = profiles;
        this.owners = owners;
        this.premium = premium;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("clan-site")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return get(guild, actor);
            AdminRouteExtension.requireMethod(method, "PUT");
            return save(guild, actor, body.get());
        }
        if (parts.length == 3 && parts[1].equals("blocks")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            return saveBlocks(guild, actor, parts[2], body.get());
        }
        if (parts.length == 3 && parts[1].equals("layout") && parts[2].equals("publish")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return publishLayout(guild, actor, body.get());
        }
        if (parts.length >= 3 && parts[1].equals("layout") && parts[2].equals("versions")) {
            if (parts.length == 3) {
                AdminRouteExtension.requireMethod(method, "GET");
                return versions(guild);
            }
            long versionId = versionId(guild, parts[3]);
            if (parts.length == 4) {
                AdminRouteExtension.requireMethod(method, "GET");
                return version(guild, versionId);
            }
            if (parts.length == 5 && parts[4].equals("restore")) {
                AdminRouteExtension.requireMethod(method, "POST");
                return restoreVersion(guild, actor, versionId, body.get());
            }
            return null;
        }
        if (parts.length != 2) return null;
        return switch (parts[1]) {
            case "layout" -> {
                if (method.equals("GET")) yield layout(guild);
                AdminRouteExtension.requireMethod(method, "PUT");
                yield saveLayout(guild, actor, body.get());
            }
            case "delete" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield delete(guild, actor, body.get().getString("confirm", ""));
            }
            case "export" -> {
                AdminRouteExtension.requireMethod(method, "GET");
                yield export(guild);
            }
            case "publish" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield publish(guild, actor, true);
            }
            case "unpublish" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield publish(guild, actor, false);
            }
            case "takedown" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield takedown(guild, actor, body.get().getString("reason", ""));
            }
            case "restore" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield takedown(guild, actor, null);
            }
            default -> null;
        };
    }

    // ---------- read ----------

    DataObject get(Guild guild, Member actor) {
        long id = guild.getIdLong();
        ClanSite site = sites.of(id);
        ClanProfile profile = profiles.profileOf(id);
        DataArray blocks = DataArray.empty();
        for (ClanSiteBlock b : repository.blocks(id)) blocks.add(blockJson(b));
        return DataObject.empty()
                .put("inBeta", sites.inBeta(id))
                .put("isOwner", owners.isOwner(guild.getJDA(), actor.getIdLong()))
                .put("slug", profile.slug())
                .put("profileProblem", profileProblem(id, profile))
                .put("site", siteJson(site))
                .put("blocks", blocks)
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

    private void requireBeta(Guild guild) {
        if (!sites.inBeta(guild.getIdLong())) throw new ApiError(403, Text.of(guild, "dashboard.the_clan_website_builder_is_in"));
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        requireBeta(guild);
        List<String> problems = new ArrayList<>();
        ClanSite current = sites.of(guild.getIdLong());
        ClanSite.Template template = ClanSite.Template.parse(body.getString("template", current.template().name())).orElse(null);
        if (template == null) problems.add("Pick one of the three templates.");
        String theme = ClanSiteRules.cleanTheme(body.optObject("theme").orElse(DataObject.empty()), problems);
        String pages = ClanSiteRules.cleanPages(body.optArray("pages").orElse(DataArray.empty()), problems);
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.the_website_can_t_be_saved"), distinct(problems));
        boolean enabled = body.getBoolean("enabled", current.enabled());
        ClanSite saved = new ClanSite(guild.getIdLong(), enabled, enabled && current.published(), template, theme, pages, actor.getIdLong(), Instant.now(),
                current.publishedAt(), current.takedownReason());
        repository.save(saved);
        return get(guild, actor);
    }

    DataObject saveBlocks(Guild guild, Member actor, String page, DataObject body) {
        requireBeta(guild);
        List<String> problems = new ArrayList<>();
        List<ClanSiteBlock> blocks = ClanSiteRules.cleanBlocks(guild.getIdLong(), page, body.optArray("blocks").orElse(DataArray.empty()), problems);
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.those_blocks_can_t_be_saved"), distinct(problems));
        repository.replaceBlocks(guild.getIdLong(), page, blocks);
        return get(guild, actor);
    }

    DataObject publish(Guild guild, Member actor, boolean publish) {
        ClanSite current = sites.of(guild.getIdLong());
        if (publish) {
            requireBeta(guild);
            List<String> problems = new ArrayList<>();
            if (!current.enabled()) problems.add("Switch the website on first.");
            String profile = profileProblem(guild.getIdLong(), profiles.profileOf(guild.getIdLong()));
            if (profile != null) problems.add(profile);
            if (current.takedownReason() != null && !current.takedownReason().isBlank()) problems.add("This website was taken down by " + BotBrand.NAME + "'s owner: " + current.takedownReason());
            if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.the_website_can_t_be_published"), problems);
        }
        repository.save(new ClanSite(current.guildId(), current.enabled(), publish, current.template(), current.theme(), current.pages(), actor.getIdLong(),
                Instant.now(), publish ? Instant.now() : current.publishedAt(), current.takedownReason()));
        return get(guild, actor);
    }

    /** The owner takes a site down (with a reason the clan sees) or lets it be published again ({@code reason} null). */
    DataObject takedown(Guild guild, Member actor, String reason) {
        if (!owners.isOwner(guild.getJDA(), actor.getIdLong())) throw new ApiError(403, Text.of(guild, "dashboard.only_jonnybot_owner_can_take_a"));
        String clean = reason == null ? null : reason.trim();
        if (clean != null && (clean.length() < 5 || clean.length() > MAX_REASON)) throw new ApiError(400, Text.of(guild, "dashboard.give_a_reason_of_5_to", MAX_REASON));
        ClanSite current = sites.of(guild.getIdLong());
        repository.save(new ClanSite(current.guildId(), current.enabled(), clean == null && current.published(), current.template(), current.theme(), current.pages(),
                actor.getIdLong(), Instant.now(), current.publishedAt(), clean));
        return get(guild, actor);
    }

    // ---------- builder v2 layout (T-301) ----------

    /**
     * The draft and whether a published copy exists, for the editor. The draft is null before the first save. {@code limits} are v1's;
     * {@code limitsV2} are layout v2's (builder v3), with the page and widget counts and kept versions of this server's plan.
     */
    DataObject layout(Guild guild) {
        long id = guild.getIdLong();
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
    DataObject saveLayout(Guild guild, Member actor, DataObject body) {
        requireBeta(guild);
        if (!layoutSaves.tryAcquire(guild.getId())) throw new ApiError(429, Text.of(guild, "dashboard.you_re_doing_that_too_fast"));
        List<String> problems = new ArrayList<>();
        String clean;
        try {
            String stored = layouts.byGuild(guild.getIdLong()).map(ClanSiteLayoutRepository.Layouts::draft).orElse(null);
            ClanSiteLayoutRulesV2.Limits limits = planLimits(guild.getIdLong()).keeping(stored);
            clean = ClanSiteLayoutRules.clean(body.optObject("layout").orElse(null), limits, problems);
        } catch (RuntimeException e) {
            problems.add("The layout couldn't be read. Reload the page and try again.");
            clean = null;
        }
        if (clean == null) throw new ApiError(400, Text.of(guild, "dashboard.the_website_can_t_be_saved"), distinct(problems));
        int rev = layouts.saveDraft(guild.getIdLong(), clean, Math.max(0, body.getInt("baseRev", 0)), actor.getIdLong());
        if (rev < 0) {
            throw new ApiError(409, Text.of(guild, "dashboard.the_website_can_t_be_saved"),
                    List.of("Someone saved this site from another tab or window. Reload to see the newest version."));
        }
        return layout(guild);
    }

    /**
     * Publishes the draft: the same checks as v1's publish (switched on, profile can carry a site, not taken down), then the copy, which is
     * also recorded as a version with the optional {@code label} (up to {@link ClanSiteLayoutRepository#MAX_VERSION_LABEL} characters).
     */
    DataObject publishLayout(Guild guild, Member actor, DataObject body) {
        if (layouts.byGuild(guild.getIdLong()).isEmpty()) {
            throw new ApiError(400, Text.of(guild, "dashboard.the_website_can_t_be_published"), List.of("Save the site before publishing it."));
        }
        String label = body.isNull("label") ? null : body.getString("label", null);
        if (label != null && label.strip().length() > ClanSiteLayoutRepository.MAX_VERSION_LABEL) {
            throw new ApiError(400, Text.of(guild, "dashboard.the_website_can_t_be_published"),
                    List.of("Keep the version name to " + ClanSiteLayoutRepository.MAX_VERSION_LABEL + " characters."));
        }
        publish(guild, actor, true);
        layouts.publishDraft(guild.getIdLong(), actor.getIdLong(), label);
        return layout(guild);
    }

    // ---------- versions (T-363, docs/BUILDER-V3.md 6.5) ----------

    /** The published versions this server's plan lets it see, newest first ({@code current}: the one the public site shows). */
    DataObject versions(Guild guild) {
        long id = guild.getIdLong();
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
    DataObject version(Guild guild, long versionId) {
        long id = guild.getIdLong();
        var found = layouts.version(id, versionId, keptVersions(id))
                .orElseThrow(() -> new ApiError(404, Text.of(guild, "dashboard.the_website_can_t_be_saved"), List.of("That version doesn't exist any more.")));
        return versionJson(found).put("layout", parse(found.layout(), true));
    }

    /** {@code {baseRev?}}: copies a version into the draft as a new revision; it goes public only when published again. */
    DataObject restoreVersion(Guild guild, Member actor, long versionId, DataObject body) {
        requireBeta(guild);
        if (!layoutSaves.tryAcquire(guild.getId())) throw new ApiError(429, Text.of(guild, "dashboard.you_re_doing_that_too_fast"));
        long id = guild.getIdLong();
        if (layouts.version(id, versionId, keptVersions(id)).isEmpty()) {
            throw new ApiError(404, Text.of(guild, "dashboard.the_website_can_t_be_saved"), List.of("That version doesn't exist any more."));
        }
        int rev = layouts.restoreVersion(id, versionId, Math.max(0, body.getInt("baseRev", 0)), actor.getIdLong());
        if (rev < 0) {
            throw new ApiError(409, Text.of(guild, "dashboard.the_website_can_t_be_saved"),
                    List.of("Someone saved this site from another tab or window. Reload to see the newest version."));
        }
        return layout(guild);
    }

    private static long versionId(Guild guild, String raw) {
        try {
            long id = Long.parseLong(raw);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // falls through to the 404
        }
        throw new ApiError(404, Text.of(guild, "dashboard.the_website_can_t_be_saved"), List.of("That version doesn't exist any more."));
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

    /** The site's plan in the layout answers ({@code {tier, until, graceUntil, source}}, tier "free" without one); the bot is in this server. */
    DataObject planJson(long guildId) {
        return com.younglings.bot.premium.Billing.planJson(guildId, true);
    }

    /**
     * Deletes the website (its v1 settings and blocks and its v2 layouts). {@code confirm} must be the site's address (or "delete" when it has
     * none), so a stray click can't do it. A site the bot's owner took down can't be deleted to clear the takedown.
     */
    DataObject delete(Guild guild, Member actor, String confirm) {
        long id = guild.getIdLong();
        ClanSite current = sites.of(id);
        if (current.takedownReason() != null && !current.takedownReason().isBlank()) {
            throw new ApiError(403, Text.of(guild, "dashboard.the_website_can_t_be_saved"), List.of("This website was taken down by " + BotBrand.NAME + "'s owner, so it can't be deleted."));
        }
        String slug = profiles.profileOf(id).slug();
        String expected = slug == null || slug.isBlank() ? "delete" : slug;
        if (!expected.equals(confirm == null ? "" : confirm.strip().toLowerCase(java.util.Locale.ROOT))) {
            throw new ApiError(400, Text.of(guild, "dashboard.the_website_can_t_be_saved"), List.of("Type " + expected + " to confirm."));
        }
        layouts.delete(id);
        repository.delete(id);
        return get(guild, actor);
    }

    // ---------- export ----------

    /** Everything the clan wrote (look, pages, blocks) and the addresses of the images it used, for "Download my site". */
    DataObject export(Guild guild) {
        long id = guild.getIdLong();
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
