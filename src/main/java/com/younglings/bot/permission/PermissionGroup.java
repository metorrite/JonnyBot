package com.younglings.bot.permission;

import java.util.List;

/**
 * A named set of server roles that works as one permission level. {@code key} is what code and saved settings refer to:
 * {@link #ADMIN}, {@link #SUPPORT} and {@link #DEVELOPER} are the three every server has (the bot's tiers), and {@link #EVENT_COORDINATOR} is
 * a fourth that opens only the event manager on the dashboard (see {@link DashboardAccess#isEventCoordinator}); anything else is a
 * group the server added itself and carries no built-in powers, only whatever a setting hands it (who may use a command, and so on).
 * <p>
 * With {@code includeHigher}, holding any role ranked above the lowest role in the group counts too, which is how the Admin
 * role has always behaved: the Admin role itself and anything senior to it (an Owner or Co-Owner role) both pass.
 */
public record PermissionGroup(long groupId, long guildId, String key, String name, boolean builtin, boolean includeHigher, List<Long> roleIds) {
    public static final String ADMIN = "admin";
    public static final String SUPPORT = "support";
    public static final String DEVELOPER = "developer";
    /** People who may run events on the dashboard and see nothing else there. Empty until a server assigns roles to it. */
    public static final String EVENT_COORDINATOR = "event-coordinator";

    public static boolean isBuiltinKey(String key) {
        return ADMIN.equals(key) || SUPPORT.equals(key) || DEVELOPER.equals(key) || EVENT_COORDINATOR.equals(key);
    }
}
