package com.younglings.bot.permission;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.configure.GuildSettingsService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
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
    private final BotConfig botConfig;

    public DashboardAccess(AdminRoleFilter adminRoleFilter, GuildSettingsService guildSettingsService, BotConfig botConfig) {
        this.adminRoleFilter = adminRoleFilter;
        this.guildSettingsService = guildSettingsService;
        this.botConfig = botConfig;
    }

    public Tier tierOf(Guild guild, Member member) {
        boolean admin = member.isOwner() || adminRoleFilter.isAuthorized(guild, member)
                || managesOtherServer(botConfig.getGuildId(), guild.getIdLong(), member.hasPermission(Permission.ADMINISTRATOR), member.hasPermission(Permission.MANAGE_SERVER));
        Long developerRoleId = guildSettingsService.getEffective(guild.getIdLong()).developerRoleId();
        return tierOf(admin, developerRoleId, member.getRoles().stream().map(Role::getIdLong).toList());
    }

    /**
     * A server other than the bot's home server hasn't set an Admin role yet when it is first installed, so whoever runs
     * it (the owner, or anyone with Administrator or Manage Server) can open its dashboard to set one up. The home
     * server keeps its own rules unchanged: only its Admin role, owner and Developer role.
     */
    static boolean managesOtherServer(Long homeGuildId, long guildId, boolean administrator, boolean manageServer) {
        boolean other = homeGuildId != null && homeGuildId != guildId;
        return other && (administrator || manageServer);
    }

    /** The decision itself, separate from looking things up so it can be tested on its own. */
    static Tier tierOf(boolean isAdminTier, Long developerRoleId, Collection<Long> memberRoleIds) {
        if (isAdminTier) return Tier.ADMIN;
        if (developerRoleId != null && memberRoleIds.contains(developerRoleId)) return Tier.DEVELOPER;
        return Tier.NONE;
    }
}
