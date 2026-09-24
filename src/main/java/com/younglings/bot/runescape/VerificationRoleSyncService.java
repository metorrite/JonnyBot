package com.younglings.bot.runescape;

import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Adds the configured "verified" role and removes the configured "unverified" role when a player
 * link is created — called from both the makeover-mage approval flow and the admin's manual-verify
 * path, so role state stays consistent no matter which one created the link.
 * <p>
 * No-ops (just logs) if either {@link BotConfig#getVerifiedRoleName()} /
 * {@link BotConfig#getUnverifiedRoleName()} isn't set, or the named role doesn't exist in the
 * guild — same "fails closed instead of guessing" philosophy as {@code AdminRoleFilter}.
 */
@BService
public class VerificationRoleSyncService {
    private static final Logger log = LoggerFactory.getLogger(VerificationRoleSyncService.class);

    private final BotConfig botConfig;

    public VerificationRoleSyncService(BotConfig botConfig) {
        this.botConfig = botConfig;
    }

    public void syncRoles(Guild guild, long discordUserId) {
        String verifiedName = botConfig.getVerifiedRoleName();
        String unverifiedName = botConfig.getUnverifiedRoleName();
        if (verifiedName == null && unverifiedName == null) return;

        guild.retrieveMemberById(discordUserId).queue(member -> {
            if (verifiedName != null) {
                Role role = findRole(guild, verifiedName);
                if (role == null) {
                    log.warn("Configured VERIFIED_ROLE_NAME '{}' not found in guild {}", verifiedName, guild.getIdLong());
                } else if (!member.getRoles().contains(role)) {
                    guild.addRoleToMember(member, role).queue(success -> {},
                            error -> log.warn("Failed to add verified role '{}' to user {}", verifiedName, discordUserId, error));
                }
            }

            if (unverifiedName != null) {
                Role role = findRole(guild, unverifiedName);
                if (role == null) {
                    log.warn("Configured UNVERIFIED_ROLE_NAME '{}' not found in guild {}", unverifiedName, guild.getIdLong());
                } else if (member.getRoles().contains(role)) {
                    guild.removeRoleFromMember(member, role).queue(success -> {},
                            error -> log.warn("Failed to remove unverified role '{}' from user {}", unverifiedName, discordUserId, error));
                }
            }
        }, error -> log.warn("Failed to retrieve member {} for verification role sync", discordUserId, error));
    }

    private Role findRole(Guild guild, String name) {
        List<Role> matches = guild.getRolesByName(name, true);
        return matches.isEmpty() ? null : matches.getFirst();
    }
}
