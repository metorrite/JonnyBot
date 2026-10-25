package com.younglings.bot.clansite;

import com.younglings.bot.access.OverallAdmins;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.discord.DiscordLookups;
import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works out a person's role on a server's clan website ({@link SiteRoles}) from Discord and the bot's settings, and is the one check every
 * clan website write goes through (T-368, docs/BUILDER-V3.md 8.2): the website never decides who may edit or publish. The viewer answer
 * the public site asks for is cached for a minute per person and site, so a role change reaches the site within a minute; changing the
 * site's role settings clears that server's answers at once.
 */
@BService
public class SiteRoleGuard {
    static final long CACHE_MILLIS = 60_000;
    private static final int SWEEP_THRESHOLD = 5_000;

    /** What the website is told about a signed-in person on a site. {@code linkedRsns} are their own linked RuneScape names in that server. */
    public record Viewer(SiteRoles.Role role, boolean inGuild, boolean staff, boolean canEdit, boolean canPublish, List<String> linkedRsns) {}

    private record Cached(Viewer viewer, long until) {}

    private final SiteRoleRepository repository;
    private final DashboardAccess access;
    private final GuildSettingsService guildSettings;
    private final PlayerLinkService links;
    private final OverallAdmins overallAdmins;
    private final ConcurrentHashMap<String, Cached> viewers = new ConcurrentHashMap<>();

    public SiteRoleGuard(SiteRoleRepository repository, DashboardAccess access, GuildSettingsService guildSettings, PlayerLinkService links,
                         OverallAdmins overallAdmins) {
        this.repository = repository;
        this.access = access;
        this.guildSettings = guildSettings;
        this.links = links;
        this.overallAdmins = overallAdmins;
    }

    /** Whether {@code userId} is an overall RuneClans admin (N5): the owner of every site, in its server or not. */
    public boolean overallAdmin(JDA jda, long userId) {
        return overallAdmins.isOverallAdmin(jda, userId);
    }

    public SiteRoles.Settings settings(long guildId) {
        return repository.get(guildId);
    }

    /** The facts about someone in the server ({@code member} null: signed in but not in it). */
    public SiteRoles.Facts facts(Guild guild, long userId, Member member) {
        Long clanMemberRole = guildSettings.getEffective(guild.getIdLong()).verifiedClanRoleId();
        boolean overall = overallAdmin(guild.getJDA(), userId);
        if (member == null) return new SiteRoles.Facts(userId, false, false, false, Set.of(), clanMemberRole).withOverallAdmin(overall);
        Set<Long> held = new HashSet<>();
        for (Role role : member.getRoles()) held.add(role.getIdLong());
        return new SiteRoles.Facts(userId, true, member.isOwner(), access.tierOf(guild, member) != DashboardAccess.Tier.NONE, held, clanMemberRole)
                .withOverallAdmin(overall);
    }

    /** {@link #factsWithoutBot} plus whether the person is an overall admin (N5) and their signed staff servers ({@code ServerFacts}). */
    public SiteRoles.Facts factsWithoutBot(JDA jda, long userId, boolean guildOwner, boolean admin, Map<Long, Long> staffServers) {
        return factsWithoutBot(userId, guildOwner, admin).withOverallAdmin(overallAdmin(jda, userId)).withStaffServers(staffServers);
    }

    /**
     * The facts about someone in a server the bot isn't in, as the website signed them: whether they own the server and whether they may manage
     * it (Administrator or Manage Server). They count as in the server; no Discord roles are known there, and no clan Member role.
     */
    public static SiteRoles.Facts factsWithoutBot(long userId, boolean guildOwner, boolean admin) {
        return new SiteRoles.Facts(userId, true, guildOwner, admin || guildOwner, Set.of(), null);
    }

    /** The person's role on this server's site, from facts already looked up ({@link #facts} or {@link #factsWithoutBot}). */
    public SiteRoles.Role roleOf(long guildId, SiteRoles.Facts facts) {
        return SiteRoles.resolve(facts, repository.get(guildId));
    }

    /** Whether the person these facts describe may do {@code action} on this server's site. */
    public boolean allows(long guildId, SiteRoles.Facts facts, SiteRoles.Action action) {
        if (facts == null) return false;
        SiteRoles.Settings s = repository.get(guildId);
        return SiteRoles.allows(SiteRoles.resolve(facts, s), action, s);
    }

    /**
     * The viewer answer for a bot-less site (the bot isn't in its server): owner and editors by user id ({@link SiteRoles#resolveWithoutBot}),
     * everyone else a guest; no linked names, since links are made in Discord. Not cached: it is one database read.
     */
    public Viewer viewerWithoutBot(JDA jda, long guildId, Long userId, Map<Long, Long> staffServers) {
        SiteRoles.Settings s = repository.get(guildId);
        SiteRoles.Role role = SiteRoles.resolveWithoutBot(userId, s);
        if (userId != null && overallAdmin(jda, userId)) role = SiteRoles.Role.OWNER;
        else if (userId != null && !role.atLeast(SiteRoles.Role.ADMIN) && s.adminAccess().staffRuleMet(staffOnly(userId, staffServers))) role = SiteRoles.Role.ADMIN;
        return new Viewer(role, false, role.atLeast(SiteRoles.Role.EDITOR), SiteRoles.allows(role, SiteRoles.Action.EDIT, s),
                SiteRoles.allows(role, SiteRoles.Action.PUBLISH, s), List.of());
    }

    public SiteRoles.Role roleOf(Guild guild, Member member) {
        return SiteRoles.resolve(facts(guild, member.getIdLong(), member), repository.get(guild.getIdLong()));
    }

    /** Whether {@code member} may do {@code action} on this server's site. */
    public boolean allows(Guild guild, Member member, SiteRoles.Action action) {
        if (member == null) return false;
        SiteRoles.Settings s = repository.get(guild.getIdLong());
        return SiteRoles.allows(SiteRoles.resolve(facts(guild, member.getIdLong(), member), s), action, s);
    }

    /**
     * {@link #viewer(Guild, long)} raised to admin when the person meets the site's staff-server rule from their signed staff servers
     * ({@code ServerFacts}). Those facts are the person's own and change with each sign-in, so this part is never cached.
     */
    public Viewer viewer(Guild guild, long userId, Map<Long, Long> staffServers) {
        Viewer viewer = viewer(guild, userId);
        if (viewer.role().atLeast(SiteRoles.Role.ADMIN) || staffServers.isEmpty()) return viewer;
        SiteRoles.Settings s = repository.get(guild.getIdLong());
        if (!s.adminAccess().staffRuleMet(staffOnly(userId, staffServers))) return viewer;
        return new Viewer(SiteRoles.Role.ADMIN, viewer.inGuild(), true, true, true, viewer.linkedRsns());
    }

    private static SiteRoles.Facts staffOnly(long userId, Map<Long, Long> staffServers) {
        return new SiteRoles.Facts(userId, false, false, false, Set.of(), null).withStaffServers(staffServers);
    }

    /** Forgets every cached viewer answer, after the overall admins changed. */
    public void forgetAll() {
        viewers.clear();
    }

    /** Stores who else may open the site admin (N5) and drops the cached answers that depended on it. */
    public void saveAdminAccess(long guildId, SiteRoles.AdminAccess adminAccess, long actorId) {
        repository.saveAdminAccess(guildId, adminAccess, actorId);
        forget(guildId);
    }

    /** The viewer answer for {@code userId}, cached for a minute. Looks the person up in the server when they aren't in the member cache. */
    public Viewer viewer(Guild guild, long userId) {
        String key = guild.getId() + ":" + userId;
        long now = System.currentTimeMillis();
        Cached hit = viewers.get(key);
        if (hit != null && hit.until() > now) return hit.viewer();
        if (viewers.size() > SWEEP_THRESHOLD) viewers.entrySet().removeIf(e -> e.getValue().until() <= now);

        SiteRoles.Settings s = repository.get(guild.getIdLong());
        SiteRoles.Facts facts = facts(guild, userId, memberOf(guild, userId));
        SiteRoles.Role role = SiteRoles.resolve(facts, s);
        List<String> rsns = facts.inGuild() ? links.getLinksForUser(guild.getIdLong(), userId).stream().map(PlayerLink::rsn).toList() : List.of();
        Viewer viewer = new Viewer(role, facts.inGuild(), SiteRoles.isStaff(role, facts, s), SiteRoles.allows(role, SiteRoles.Action.EDIT, s),
                SiteRoles.allows(role, SiteRoles.Action.PUBLISH, s), rsns);
        viewers.put(key, new Cached(viewer, now + CACHE_MILLIS));
        return viewer;
    }

    /** Forgets the cached viewer answers for a server, after its role settings changed. */
    public void forget(long guildId) {
        String prefix = guildId + ":";
        viewers.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Stores the role lists and the switch, and drops the cached answers that depended on them. */
    public void save(long guildId, SiteRoles.Settings settings, long actorId) {
        repository.save(guildId, settings, actorId);
        forget(guildId);
    }

    /** Hands the site to {@code userId} (null: back to the server owner alone). */
    public void handOver(long guildId, Long userId, long actorId) {
        repository.setClaimedBy(guildId, userId, actorId);
        forget(guildId);
    }

    /** The first person to set the site up becomes the one who claimed it (an owner of the site), unless someone already did. */
    public void claim(long guildId, long userId) {
        if (repository.claimIfUnclaimed(guildId, userId)) forget(guildId);
    }

    public void delete(long guildId) {
        repository.delete(guildId);
        forget(guildId);
    }

    /**
     * The person as a member of the server, or null when they aren't in it. Any other failure (Discord didn't answer) is thrown, so a
     * passing outage never caches someone as a guest.
     */
    private static Member memberOf(Guild guild, long userId) {
        return DiscordLookups.memberOrSlow(guild, userId);
    }
}
