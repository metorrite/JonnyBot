package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileService;
import com.younglings.bot.clansite.ClanSite;
import com.younglings.bot.clansite.ClanSiteBlock;
import com.younglings.bot.clansite.ClanSiteRepository;
import com.younglings.bot.clansite.ClanSiteRules;
import com.younglings.bot.clansite.ClanSiteService;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.notice.BotOwners;
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

/**
 * A server's clan website (website builder) for the dashboard's Clan website page (docs/DOMAINS-AND-SITE-BUILDER.md B5, website repo):
 * <ul>
 *   <li>{@code GET clan-site}: the site, its blocks, whether the server is in the builder's beta and whether its clan profile can carry a site</li>
 *   <li>{@code PUT clan-site} {@code {enabled, template, theme, pages}}: checked whole before anything is stored; switching off unpublishes</li>
 *   <li>{@code PUT clan-site/blocks/{page}} {@code {blocks}}: one page's content blocks, in order</li>
 *   <li>{@code POST clan-site/publish}, {@code POST clan-site/unpublish}</li>
 *   <li>{@code GET clan-site/export}: everything the clan wrote, for "Download my site"</li>
 *   <li>{@code POST clan-site/takedown} {@code {reason}} and {@code POST clan-site/restore}: the bot's owner only (a report was upheld)</li>
 * </ul>
 * Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard here and audit-logs every write.
 */
@BService
public class ClanSiteAdminApi implements AdminRouteExtension {
    static final int MAX_REASON = 300;

    private final ClanSiteRepository repository;
    private final ClanSiteService sites;
    private final ClanProfileService profiles;
    private final BotOwners owners;

    public ClanSiteAdminApi(ClanSiteRepository repository, ClanSiteService sites, ClanProfileService profiles, BotOwners owners) {
        this.repository = repository;
        this.sites = sites;
        this.profiles = profiles;
        this.owners = owners;
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
        if (parts.length != 2) return null;
        return switch (parts[1]) {
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
            if (current.takedownReason() != null && !current.takedownReason().isBlank()) problems.add("This website was taken down by JonnyBot's owner: " + current.takedownReason());
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
                .put("site", siteJson(site)).put("blocks", blocks).put("images", DataArray.fromCollection(images));
    }

    private static List<String> distinct(List<String> problems) {
        return problems.stream().distinct().toList();
    }
}
