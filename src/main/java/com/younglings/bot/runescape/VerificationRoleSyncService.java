package com.younglings.bot.runescape;

import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            if (verifiedRoleId != null) {
                Role role = guild.getRoleById(verifiedRoleId);
                if (role == null) {
                    log.warn("Configured verified role {} not found in guild {}", verifiedRoleId, guild.getIdLong());
                } else if (!member.getRoles().contains(role)) {
                    guild.addRoleToMember(member, role).queue(success -> {},
                            error -> log.warn("Failed to add verified role {} to user {}", verifiedRoleId, discordUserId, error));
                }
            }

            if (unverifiedRoleId != null) {
                Role role = guild.getRoleById(unverifiedRoleId);
                if (role == null) {
                    log.warn("Configured unverified role {} not found in guild {}", unverifiedRoleId, guild.getIdLong());
                } else if (member.getRoles().contains(role)) {
                    guild.removeRoleFromMember(member, role).queue(success -> {},
                            error -> log.warn("Failed to remove unverified role {} from user {}", unverifiedRoleId, discordUserId, error));
                }
            }
        }, error -> log.warn("Failed to retrieve member {} for verification role sync", discordUserId, error));
    }
}
