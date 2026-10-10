package com.younglings.bot.permission;

import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

/**
 * Who may open the website's admin dashboard: the Admin tier (see {@link AdminRoleFilter#isAuthorized}) or the
 * server owner, or anyone in the Developer group. The website asks the bot on every request
 * rather than caching an answer, so removing someone's role locks them out immediately.
 */
@BService
public class DashboardAccess {
    public enum Tier { ADMIN, DEVELOPER, NONE }

    private final AdminRoleFilter adminRoleFilter;
    private final PermissionGroupService groups;
    private final BotConfig botConfig;

    public DashboardAccess(AdminRoleFilter adminRoleFilter, PermissionGroupService groups, BotConfig botConfig) {
        this.adminRoleFilter = adminRoleFilter;
        this.groups = groups;
        this.botConfig = botConfig;
    }

    public Tier tierOf(Guild guild, Member member) {
        boolean admin = member.isOwner() || adminRoleFilter.isAuthorized(guild, member)
                || managesOtherServer(botConfig.getGuildId(), guild.getIdLong(), member.hasPermission(Permission.ADMINISTRATOR), member.hasPermission(Permission.MANAGE_SERVER));
        return tierOf(admin, admin || groups.isMember(guild, member, PermissionGroup.DEVELOPER));
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
    static Tier tierOf(boolean isAdminTier, boolean isDeveloper) {
        if (isAdminTier) return Tier.ADMIN;
        return isDeveloper ? Tier.DEVELOPER : Tier.NONE;
    }
}
