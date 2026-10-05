package com.younglings.bot.permission;

import com.younglings.bot.configure.GuildSettingsService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

/**
 * "Member level" access, for features meant for verified clan members and the staff above them (polls
 * today) — not for Guests, who haven't been accepted yet. A member is someone holding the configured
 * Verified Clan Role (Youngling Member), or anyone in the Support tier or above (see
 * {@link AdminRoleFilter#isSupportTier}) — so staff never need the Member role just to use these.
 * <p>
 * Checked in the bot itself rather than relying on the Integrations page's command permissions, so
 * the rule is the same whatever those say. Fails closed: with no Verified Clan Role configured, only
 * the Support tier and above get through.
 */
@BService
public class MemberAccess {
    private final GuildSettingsService guildSettingsService;
    private final AdminRoleFilter adminRoleFilter;

    public MemberAccess(GuildSettingsService guildSettingsService, AdminRoleFilter adminRoleFilter) {
        this.guildSettingsService = guildSettingsService;
        this.adminRoleFilter = adminRoleFilter;
    }

    public boolean isMemberTier(Guild guild, Member member) {
        if (adminRoleFilter.isSupportTier(guild, member)) return true;

        Long memberRoleId = guildSettingsService.getEffective(guild.getIdLong()).verifiedClanRoleId();
        return memberRoleId != null && member.getRoles().stream().anyMatch(role -> role.getIdLong() == memberRoleId);
    }
}
