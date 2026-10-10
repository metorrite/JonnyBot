package com.younglings.bot.hub;

import java.util.List;

/**
 * One server's settings for one Hub command.
 *
 * @param enabled      off means nobody can use the command, admins included, until it is switched back on
 * @param customAccess false: the command's own rule applies. true: only admins plus {@code allowedRefs} may use it
 * @param allowedRefs  who, besides admins, when {@code customAccess}: {@code group:<key>} or {@code role:<id>}, see
 *                     {@code PermissionGroupService#matches}
 * @param channelIds   where members may use the command; empty means anywhere. Admins are exempt, so they can't lock
 *                     themselves out of the channel they manage it from
 * @param extras       settings only some commands have, as JSON: see {@link HubService} for each command's
 */
public record HubSettings(long guildId, String commandKey, boolean enabled, boolean customAccess, List<String> allowedRefs,
                          List<Long> channelIds, String extras) {
    public static HubSettings defaults(long guildId, String commandKey) {
        return new HubSettings(guildId, commandKey, true, false, List.of(), List.of(), "{}");
    }
}
