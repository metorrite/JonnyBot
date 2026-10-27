package com.younglings.bot.internal;

import com.younglings.bot.clansite.SiteRoleGuard;
import com.younglings.bot.clansite.SiteRoles;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.member.MemberProfileRepository;
import com.younglings.bot.member.MemberProfileRepository.PrivacyDefaults;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The site admin's member pages (docs/BUILDER-V3.md 8.5), reached through {@link ClanSiteAdminApi}, which has already checked the asker is an
 * admin of the site:
 * <ul>
 *   <li>{@code GET|PUT clan-site/member-privacy} {@code {hideFromLeaderboards, hideAdventureLog, hideDiscordLink}} (T-395): what is hidden for
 *   members who haven't chosen their own privacy toggles. All off by default. A default only ever hides more: a member who turned a toggle on
 *   stays hidden whatever the defaults, and one who chose their toggles keeps them.</li>
 *   <li>{@code GET clan-site/roles/people?q=&role=&page=} (T-396): who has which site role, by display name, a page at a time. No Discord ids
 *   where the bot is in the server; a bot-less site can't see its members, so it lists only the people it names by user id (the owner who
 *   claimed it, site admins and editors), which its admins already see on the Roles page.</li>
 *   <li>{@code clan-site/cockpit...} (T-593): the Clan health page ({@link SiteCockpitAdmin})</li>
 * </ul>
 */
@BService
public class SiteMembersAdmin {
    static final int PAGE_SIZE = 25;
    /** At most this many name matches get a role worked out per search; a longer list says {@code truncated} and asks for a narrower search. */
    static final int MAX_SCAN = 1_000;
    static final int MAX_QUERY = 64;
    static final List<String> ROLE_FILTERS = List.of("owner", "admin", "editor", "member", "guest", "staff");

    private final MemberProfileRepository profiles;
    private final SiteRoleGuard siteRoles;
    private final SiteCache cache;
    private final SiteCockpitAdmin cockpit;
    /** Searches per person: a burst of 5 (typing), then 2 a second. Reads aren't otherwise limited, and a search walks the member list. */
    private final RateLimiter searches = new RateLimiter(5, 500);

    public SiteMembersAdmin(MemberProfileRepository profiles, SiteRoleGuard siteRoles, SiteCache cache, SiteCockpitAdmin cockpit) {
        this.profiles = profiles;
        this.siteRoles = siteRoles;
        this.cache = cache;
        this.cockpit = cockpit;
    }

    // ---------- the Clan health page (T-593) ----------

    /** {@code clan-site/cockpit...}; null for a path it doesn't serve. */
    DataObject cockpit(SiteCaller c, String method, String[] parts, Supplier<DataObject> body) {
        return cockpit.route(c, method, parts, body);
    }

    // ---------- member privacy defaults (T-395) ----------

    DataObject privacy(SiteCaller c, String method, Supplier<DataObject> body) {
        if (!method.equals("GET")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            PrivacyDefaults current = profiles.privacyDefaults(c.guildId());
            DataObject b = body.get();
            PrivacyDefaults next = new PrivacyDefaults(flag(c, b, "hideFromLeaderboards", current.hideFromLeaderboards()),
                    flag(c, b, "hideAdventureLog", current.hideAdventureLog()), flag(c, b, "hideDiscordLink", current.hideDiscordLink()));
            profiles.savePrivacyDefaults(c.guildId(), next, c.userId());
            // the defaults change who shows in many cached answers, the clan site's included
            cache.clear();
        }
        return privacyJson(profiles.privacyDefaults(c.guildId()));
    }

    /** Drops the server's defaults with its deleted website. */
    void forget(long guildId) {
        profiles.deletePrivacyDefaults(guildId);
        cockpit.forget(guildId);
        cache.clear();
    }

    static DataObject privacyJson(PrivacyDefaults d) {
        return DataObject.empty().put("hideFromLeaderboards", d.hideFromLeaderboards()).put("hideAdventureLog", d.hideAdventureLog())
                .put("hideDiscordLink", d.hideDiscordLink());
    }

    /** A true/false setting, or what is stored when the save leaves it out. Anything else is refused. */
    private static boolean flag(SiteCaller c, DataObject b, String key, boolean current) {
        if (!b.hasKey(key)) return current;
        if (b.isType(key, DataType.BOOLEAN)) return b.getBoolean(key);
        throw new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), List.of("Each privacy default is on or off."));
    }

    // ---------- who has which site role (T-396) ----------

    /** One row of the search: the name to show and, on a bot-less site only, the user id it is known by. */
    record Person(String name, String username, String avatarUrl, String userId, SiteRoles.Role role, boolean staff) {}

    DataObject people(SiteCaller c, DataObject query) {
        if (!searches.tryAcquire(Long.toString(c.userId()))) throw new ApiError(429, Text.of(c.guildId(), "dashboard.you_re_doing_that_too_fast"));
        String q = clean(query.getString("q", ""));
        String role = query.getString("role", "").strip().toLowerCase(Locale.ROOT);
        if (!role.isEmpty() && !ROLE_FILTERS.contains(role)) {
            throw new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), List.of("Pick one of the site roles to filter by."));
        }
        int page = 1;
        try {
            page = Math.max(1, Integer.parseInt(query.getString("page", "1").strip()));
        } catch (NumberFormatException ignored) {
            // the first page
        }
        List<Person> people = new ArrayList<>();
        boolean truncated = false;
        if (c.botless()) {
            people.addAll(knownPeople(siteRoles.settings(c.guildId())));
        } else {
            List<Member> matches = new ArrayList<>();
            for (Member m : c.guild().getMemberCache()) {
                if (m.getUser().isBot() || !matches(q, m.getEffectiveName(), m.getUser().getName())) continue;
                if (matches.size() >= MAX_SCAN) {
                    truncated = true;
                    break;
                }
                matches.add(m);
            }
            for (SiteRoleGuard.Placed p : siteRoles.rolesOf(c.guild(), matches)) {
                Member m = p.member();
                people.add(new Person(m.getEffectiveName(), m.getUser().getName(), m.getEffectiveAvatarUrl(), null, p.role(), p.staff()));
            }
        }
        if (c.botless()) people.removeIf(p -> !matches(q, p.name(), p.userId()));
        return pageJson(select(people, role), page, truncated).put("available", !c.botless());
    }

    /** On a bot-less site, the people it names by Discord user id, highest role first (someone named twice shows once, at the higher role). */
    static List<Person> knownPeople(SiteRoles.Settings s) {
        java.util.Map<Long, SiteRoles.Role> byId = new java.util.LinkedHashMap<>();
        if (s.claimedBy() != null) byId.put(s.claimedBy(), SiteRoles.Role.OWNER);
        for (Long id : s.adminAccess().userIds()) byId.putIfAbsent(id, SiteRoles.Role.ADMIN);
        for (Long id : s.editorUserIds()) byId.putIfAbsent(id, SiteRoles.Role.EDITOR);
        List<Person> out = new ArrayList<>();
        byId.forEach((id, role) -> out.add(new Person(null, null, null, String.valueOf(id), role, role.atLeast(SiteRoles.Role.EDITOR))));
        return out;
    }

    static String clean(String raw) {
        String q = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return q.length() > MAX_QUERY ? q.substring(0, MAX_QUERY) : q;
    }

    /** An empty search matches everyone; otherwise any of the names containing it, ignoring case. */
    static boolean matches(String q, String... names) {
        if (q.isEmpty()) return true;
        for (String n : names) if (n != null && n.toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    /** The people with {@code role} ("staff": anyone who counts as staff; empty: everyone), highest role first, then by name. */
    static List<Person> select(List<Person> people, String role) {
        return people.stream()
                .filter(p -> role.isEmpty() || (role.equals("staff") ? p.staff() : p.role().key().equals(role)))
                .sorted(Comparator.comparing(Person::role).reversed()
                        .thenComparing(p -> (p.name() == null ? p.userId() : p.name()).toLowerCase(Locale.ROOT)))
                .toList();
    }

    static DataObject pageJson(List<Person> people, int page, boolean truncated) {
        int pages = Math.max(1, (people.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int shown = Math.min(page, pages);
        DataArray rows = DataArray.empty();
        for (Person p : people.subList((shown - 1) * PAGE_SIZE, Math.min(people.size(), shown * PAGE_SIZE))) {
            DataObject row = DataObject.empty().put("name", p.name()).put("role", p.role().key()).put("staff", p.staff());
            if (p.username() != null) row.put("username", p.username());
            if (p.avatarUrl() != null) row.put("avatarUrl", p.avatarUrl());
            if (p.userId() != null) row.put("userId", p.userId());
            rows.add(row);
        }
        return DataObject.empty().put("people", rows).put("total", people.size()).put("page", shown).put("pages", pages)
                .put("pageSize", PAGE_SIZE).put("truncated", truncated);
    }
}
