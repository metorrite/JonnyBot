package com.younglings.bot.permission;

import com.younglings.bot.configure.GuildSettingsService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.util.Collection;

/**
 * Who may open the website's admin dashboard: the Admin tier (see {@link AdminRoleFilter#isAuthorized}) or the
 * server owner, or anyone holding the configured Developer role. The website asks the bot on every request
 * rather than caching an answer, so removing someone's role locks them out immediately.
 */
@BService
public class DashboardAccess {
    public enum Tier { ADMIN, DEVELOPER, NONE }

    private final AdminRoleFilter adminRoleFilter;
    private final GuildSettingsService guildSettingsService;

    public DashboardAccess(AdminRoleFilter adminRoleFilter, GuildSettingsService guildSettingsService) {
        this.adminRoleFilter = adminRoleFilter;
        this.guildSettingsService = guildSettingsService;
    }

    public Tier tierOf(Guild guild, Member member) {
        boolean admin = member.isOwner() || adminRoleFilter.isAuthorized(guild, member);
        Long developerRoleId = guildSettingsService.getEffective(guild.getIdLong()).developerRoleId();
        return tierOf(admin, developerRoleId, member.getRoles().stream().map(Role::getIdLong).toList());
    }

    /** The decision itself, separate from looking things up so it can be tested on its own. */
    static Tier tierOf(boolean isAdminTier, Long developerRoleId, Collection<Long> memberRoleIds) {
        if (isAdminTier) return Tier.ADMIN;
        if (developerRoleId != null && memberRoleIds.contains(developerRoleId)) return Tier.DEVELOPER;
        return Tier.NONE;
    }
}
