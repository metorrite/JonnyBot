package com.younglings.bot.runescape;

import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adds a configured "verified" role and removes a configured "unverified" role when a player link
 * is created — called from both the admin-review approval flow and the admin's manual-verify path,
 * so role state stays consistent no matter which one created the link.
 * <p>
 * Which "verified" role depends on whether {@code rsn} is a currently-tracked clan member (see
 * {@link ClanSyncService#getRoster}): {@link GuildSettings#verifiedClanRoleId()} if so, otherwise
 * {@link GuildSettings#verifiedNonClanRoleId()} — the latter is meant for a role something else
 * (e.g. a server join flow) usually already grants, so this only fills it in for a verified member
 * who doesn't have it yet. All three role slots (both verified variants, plus
 * {@link GuildSettings#unverifiedRoleId()}) are independently optional — configured via a role
 * dropdown under {@code /configure}'s Verification section, not typed by name, so there's no name
 * to mistype and nothing to look up by string match. A slot left unset (or pointing at a role no
 * longer in the guild) is simply skipped and logged, never guessed — same "fails closed" philosophy
 * as {@code AdminRoleFilter}.
 * <p>
 * The two roles can legitimately be the <em>same</em> role: with Guest as both the "unverified" and the
 * "not a clan member" role, a verified non-clan player keeps Guest while a clan member trades Guest for
 * Member. So the unverified role is never removed if it is the very role being granted, and both
 * changes are sent to Discord as one update — nobody is ever left holding neither role, even briefly.
 */
@BService
public class VerificationRoleSyncService {
    private static final Logger log = LoggerFactory.getLogger(VerificationRoleSyncService.class);

    private final GuildSettingsService guildSettingsService;
    private final ClanSyncService clanSyncService;

    public VerificationRoleSyncService(GuildSettingsService guildSettingsService, ClanSyncService clanSyncService) {
        this.guildSettingsService = guildSettingsService;
        this.clanSyncService = clanSyncService;
    }

    public void syncRoles(Guild guild, long discordUserId, String rsn) {
        GuildSettings settings = guildSettingsService.getEffective(guild.getIdLong());
        boolean inClan = clanSyncService.getRoster(guild.getIdLong(), true).stream()
                .anyMatch(member -> member.rsn().equalsIgnoreCase(rsn));
        Long verifiedRoleId = inClan ? settings.verifiedClanRoleId() : settings.verifiedNonClanRoleId();
        Long unverifiedRoleId = settings.unverifiedRoleId();
        if (verifiedRoleId == null && unverifiedRoleId == null) return;

        guild.retrieveMemberById(discordUserId).queue(member -> {
            Set<Long> held = new HashSet<>();
            for (Role role : member.getRoles()) held.add(role.getIdLong());

            RolePlan plan = plan(verifiedRoleId, unverifiedRoleId, held, id -> guild.getRoleById(id) != null);
            plan.missing().forEach(id -> log.warn("Configured verification role {} not found in guild {}", id, guild.getIdLong()));
            if (plan.add().isEmpty() && plan.remove().isEmpty()) return;

            List<Role> add = plan.add().stream().map(guild::getRoleById).toList();
            List<Role> remove = plan.remove().stream().map(guild::getRoleById).toList();
            guild.modifyMemberRoles(member, add, remove).queue(success -> {},
                    error -> log.warn("Failed to update verification roles (add {}, remove {}) for user {}", plan.add(), plan.remove(), discordUserId, error));
        }, error -> log.warn("Failed to retrieve member {} for verification role sync", discordUserId, error));
    }

    /** What to change for one member: role ids to add, role ids to remove, and configured ids that no longer exist in the guild. */
    record RolePlan(Set<Long> add, Set<Long> remove, Set<Long> missing) {}

    /**
     * Pure decision, no Discord calls. The verified role is added if the member lacks it; the unverified
     * role is removed if the member holds it — unless it is the same role as the verified one, which is
     * simply kept. A configured role that no longer exists is skipped and reported, never guessed at.
     */
    static RolePlan plan(Long verifiedRoleId, Long unverifiedRoleId, Set<Long> memberRoleIds, java.util.function.Predicate<Long> roleExists) {
        Set<Long> add = new HashSet<>();
        Set<Long> remove = new HashSet<>();
        Set<Long> missing = new HashSet<>();

        if (verifiedRoleId != null) {
            if (!roleExists.test(verifiedRoleId)) missing.add(verifiedRoleId);
            else if (!memberRoleIds.contains(verifiedRoleId)) add.add(verifiedRoleId);
        }
        if (unverifiedRoleId != null) {
            if (!roleExists.test(unverifiedRoleId)) missing.add(unverifiedRoleId);
            else if (memberRoleIds.contains(unverifiedRoleId) && !unverifiedRoleId.equals(verifiedRoleId)) remove.add(unverifiedRoleId);
        }
        return new RolePlan(add, remove, missing);
    }
}
