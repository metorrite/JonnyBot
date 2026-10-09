package com.younglings.bot.runescape;

import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Who holds the Guest role without ever having linked a RuneScape name: members someone gave Guest by hand
 * and who never went through {@code /rs}. Giving Guest by hand is allowed — this only makes it visible, it
 * never takes the role away.
 * <p>
 * "Guest" here is the role {@code /rs} grants on submission (the Onboarding role), falling back to the
 * "verified, not in the clan" role and then the "unverified" role, since on this server those are one role.
 */
@BService
public class UnverifiedGuestService {
    private final GuildSettingsService guildSettingsService;
    private final PlayerLinkService linkService;

    public UnverifiedGuestService(GuildSettingsService guildSettingsService, PlayerLinkService linkService) {
        this.guildSettingsService = guildSettingsService;
        this.linkService = linkService;
    }

    /** One member holding the Guest role, with what {@link #select} needs to judge them. */
    public record Candidate(long userId, boolean bot, boolean administrator, boolean hasClanMemberRole, OffsetDateTime joinedServerAt) {}

    /** A Guest-role holder with no verified link and no pending request, and when they joined the server. */
    public record UnverifiedGuest(long userId, OffsetDateTime joinedServerAt) {}

    /** {@code guestRole} is {@code null} when no Guest-style role is configured, so there is nothing to list. */
    public record Result(Role guestRole, List<UnverifiedGuest> guests) {}

    /**
     * Pure decision: of the Guest-role holders, those who have no verified link and no pending request. Bots,
     * administrators and people who already hold the clan Member role are left out, since none of them is
     * waiting to be verified as a guest. Longest-standing first.
     */
    static List<UnverifiedGuest> select(List<Candidate> holders, Set<Long> linkedUserIds, Set<Long> pendingUserIds) {
        return holders.stream()
                .filter(c -> !c.bot() && !c.administrator() && !c.hasClanMemberRole())
                .filter(c -> !linkedUserIds.contains(c.userId()) && !pendingUserIds.contains(c.userId()))
                .map(c -> new UnverifiedGuest(c.userId(), c.joinedServerAt()))
                .sorted(Comparator.comparing(UnverifiedGuest::joinedServerAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** The role this treats as Guest, or {@code null} if none of the three verification roles is configured. */
    static Long guestRoleId(GuildSettings settings) {
        if (settings.onboardingRoleId() != null) return settings.onboardingRoleId();
        if (settings.verifiedNonClanRoleId() != null) return settings.verifiedNonClanRoleId();
        return settings.unverifiedRoleId();
    }

    /**
     * Finds them. Asynchronous because the bot only keeps online members cached, so everyone holding the role
     * has to be fetched from Discord first.
     */
    public void find(Guild guild, Consumer<Result> onDone, Consumer<Throwable> onError) {
        long guildId = guild.getIdLong();
        GuildSettings settings = guildSettingsService.getEffective(guildId);
        Long roleId = guestRoleId(settings);
        Role guestRole = roleId == null ? null : guild.getRoleById(roleId);
        if (guestRole == null) {
            onDone.accept(new Result(null, List.of()));
            return;
        }

        Long clanRoleId = settings.verifiedClanRoleId();
        guild.findMembersWithRoles(guestRole).onSuccess(members -> {
            Set<Long> linked = new HashSet<>();
            for (PlayerLink link : linkService.getAllLinks(guildId)) linked.add(link.discordUserId());
            Set<Long> pending = new HashSet<>();
            for (VerificationAttempt attempt : linkService.getPendingAttempts(guildId)) pending.add(attempt.discordUserId());

            List<Candidate> holders = members.stream().map(member -> toCandidate(member, clanRoleId)).toList();
            onDone.accept(new Result(guestRole, select(holders, linked, pending)));
        }).onError(onError);
    }

    private static Candidate toCandidate(Member member, Long clanRoleId) {
        boolean clanMember = clanRoleId != null && member.getRoles().stream().anyMatch(role -> role.getIdLong() == clanRoleId);
        return new Candidate(member.getIdLong(), member.getUser().isBot(), member.hasPermission(Permission.ADMINISTRATOR), clanMember, member.getTimeJoined());
    }
}
