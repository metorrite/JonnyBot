package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfile.Focus;
import com.younglings.bot.clanprofile.ClanProfile.Recruiting;
import com.younglings.bot.clanprofile.ClanProfile.Visibility;
import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.clanprofile.ClanProfileService;
import com.younglings.bot.clanprofile.ProfileRules;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A server's clan profile for the website: {@code GET clan-profile}, {@code PUT clan-profile} (a save is checked whole before anything is
 * stored) and {@code POST clan-profile/verify} (the person asking proves they lead the clan). Reached only through {@link TicketAdminApi},
 * which has already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class ClanProfileAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(ClanProfileAdminApi.class);

    private final ClanProfileRepository repository;
    private final ClanProfileService service;

    public ClanProfileAdminApi(ClanProfileRepository repository, ClanProfileService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("clan-profile")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return get(guild);
            AdminRouteExtension.requireMethod(method, "PUT");
            return save(guild, actor, body.get());
        }
        if (parts.length == 2 && parts[1].equals("verify")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return verify(guild, actor);
        }
        return null;
    }

    DataObject get(Guild guild) {
        return get(guild.getIdLong(), guild.getName());
    }

    /** The profile of any server; {@code serverName} (null for a bot-less site, whose server the bot can't see) only suggests a name. */
    DataObject get(long guildId, String serverName) {
        ClanProfile p = service.profileOf(guildId);
        String clan = service.clanNameOf(guildId);
        DataArray focus = DataArray.empty();
        for (Focus f : Focus.values()) if (p.focus().contains(f)) focus.add(f.name());
        return DataObject.empty()
                .put("visibility", p.visibility().name()).put("slug", p.slug()).put("displayName", p.displayName()).put("tagline", p.tagline())
                .put("description", p.description()).put("logoUrl", p.logoUrl()).put("colour", p.colour()).put("timezone", p.timezone())
                .put("focus", focus).put("recruiting", p.recruiting().name()).put("requirements", p.requirements()).put("applyUrl", p.applyUrl())
                .put("leaderboardOptIn", p.leaderboardOptIn())
                .put("currentClanName", clan).put("verified", ProfileRules.verified(p, clan)).put("verifiedClanName", p.verifiedClanName())
                .put("listing", ProfileRules.listing(p, clan).name()).put("suspended", p.suspended()).put("suspendedReason", p.suspendedReason())
                .put("memberCount", clan == null ? 0 : service.memberCount(guildId))
                .put("suggestedName", clan != null ? clan : serverName != null ? serverName : "")
                .put("options", options())
                .put("limits", DataObject.empty().put("name", ProfileRules.MAX_NAME).put("tagline", ProfileRules.MAX_TAGLINE)
                        .put("description", ProfileRules.MAX_DESCRIPTION).put("requirements", ProfileRules.MAX_REQUIREMENTS));
    }

    private static DataObject options() {
        DataArray visibility = DataArray.empty();
        for (Visibility v : Visibility.values()) visibility.add(DataObject.empty().put("key", v.name()).put("label", v.label()));
        DataArray recruiting = DataArray.empty();
        for (Recruiting r : Recruiting.values()) recruiting.add(DataObject.empty().put("key", r.name()).put("label", r.label()));
        DataArray focus = DataArray.empty();
        for (Focus f : Focus.values()) focus.add(DataObject.empty().put("key", f.name()).put("label", f.label()));
        return DataObject.empty().put("visibility", visibility).put("recruiting", recruiting).put("focus", focus);
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        return save(guild.getIdLong(), guild.getName(), actor.getIdLong(), body);
    }

    /** {@link #save(Guild, Member, DataObject)} for any server ({@code serverName} null for a bot-less site). */
    DataObject save(long guildId, String serverName, long actorId, DataObject body) {
        ClanProfile current = service.profileOf(guildId);
        String clan = service.clanNameOf(guildId);
        List<String> problems = new ArrayList<>();

        String name = text(body, "displayName", current.displayName());
        String tagline = text(body, "tagline", current.tagline());
        String description = text(body, "description", current.description());
        String requirements = text(body, "requirements", current.requirements());
        String logoUrl = optional(body, "logoUrl", current.logoUrl());
        String applyUrl = optional(body, "applyUrl", current.applyUrl());
        String timezone = optional(body, "timezone", current.timezone());
        Integer colour = body.hasKey("colour") ? (body.isNull("colour") ? null : body.getInt("colour", -1)) : current.colour();
        problems.addAll(ProfileRules.fieldProblems(name, tagline, description, requirements, logoUrl, applyUrl, colour, timezone));

        Visibility visibility = current.visibility();
        if (body.hasKey("visibility")) {
            try {
                visibility = Visibility.valueOf(body.getString("visibility", "").strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add("Choose private, unlisted or public.");
            }
        }
        Recruiting recruiting = current.recruiting();
        if (body.hasKey("recruiting")) {
            try {
                recruiting = Recruiting.valueOf(body.getString("recruiting", "").strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add("Choose whether the clan is recruiting.");
            }
        }
        Set<Focus> focus = EnumSet.noneOf(Focus.class);
        if (body.hasKey("focus") && !body.isNull("focus")) {
            DataArray given = body.getArray("focus");
            for (int i = 0; i < given.length(); i++) {
                try {
                    focus.add(Focus.valueOf(given.getString(i).strip().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    problems.add("One of the focus choices isn't one the bot knows.");
                }
            }
        } else {
            focus.addAll(current.focus());
        }

        boolean leaderboardOptIn = body.hasKey("leaderboardOptIn") ? body.getBoolean("leaderboardOptIn", false) : current.leaderboardOptIn();

        String slug = current.slug();
        if (body.hasKey("slug") && !body.isNull("slug") && !body.getString("slug", "").isBlank()) {
            slug = body.getString("slug").strip().toLowerCase(Locale.ROOT);
            // An address the clan already has is kept as it is, even if the rules have since grown (T-290 reserved more words).
            String slugProblem = slug.equals(current.slug()) ? null : ProfileRules.slugProblem(slug);
            if (slugProblem != null) problems.add(slugProblem);
            else if (repository.slugTaken(slug, guildId)) {
                problems.add("Another clan uses the address \"" + slug + "\", or gave it up less than " + ProfileRules.SLUG_GRACE.toDays() + " days ago. Choose another.");
            }
        } else if (slug == null) {
            String fallback = clan != null ? clan : serverName != null ? serverName : "clan";
            slug = freeSlug(ProfileRules.slugify(name.isBlank() ? fallback : name), guildId);
        }

        if (problems.isEmpty() && visibility != current.visibility()) {
            String blocked = ProfileRules.visibilityProblem(current, clan, visibility);
            if (blocked != null) problems.add(blocked);
        } else if (problems.isEmpty() && visibility != Visibility.PRIVATE && current.suspended()) {
            problems.add(ProfileRules.visibilityProblem(current, clan, visibility));
        }
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guildId, "dashboard.the_clan_profile_can_t_be"), problems.stream().filter(p -> p != null).distinct().toList());

        repository.saveContent(new ClanProfile(guildId, visibility, slug, name.strip(), tagline.strip(), description.strip(), blank(logoUrl), colour, blank(timezone),
                focus, recruiting, requirements.strip(), blank(applyUrl), leaderboardOptIn, null, null, null, null, null, null));
        log.info("Dashboard: {} saved the clan profile ({})", actorId, visibility);
        return get(guildId, serverName);
    }

    DataObject verify(Guild guild, Member actor) {
        var result = service.verify(guild.getIdLong(), actor.getIdLong());
        if (!result.ok()) throw new ApiError(400, Text.of(guild, "dashboard.the_clan_couldn_t_be_verified"), List.of(result.problem()));
        log.info("Dashboard: {} verified the clan for guild {}", actor.getId(), guild.getId());
        return get(guild);
    }

    /** The slug as given, or with -2, -3 and so on added until no other clan has it. */
    private String freeSlug(String base, long guildId) {
        String start = ProfileRules.slugProblem(base) == null ? base : "clan";
        String slug = start;
        for (int i = 2; repository.slugTaken(slug, guildId) || ProfileRules.slugProblem(slug) != null; i++) {
            String suffix = "-" + i;
            slug = (start.length() + suffix.length() > ProfileRules.MAX_SLUG ? start.substring(0, ProfileRules.MAX_SLUG - suffix.length()) : start) + suffix;
        }
        return slug;
    }

    private static String text(DataObject body, String key, String fallback) {
        return body.hasKey(key) && !body.isNull(key) ? body.getString(key, "") : (fallback == null ? "" : fallback);
    }

    private static String optional(DataObject body, String key, String fallback) {
        return body.hasKey(key) ? (body.isNull(key) ? null : body.getString(key, "")) : fallback;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
