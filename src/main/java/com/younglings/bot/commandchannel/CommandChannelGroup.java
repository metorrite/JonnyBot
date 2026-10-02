package com.younglings.bot.commandchannel;

import java.util.Set;

/**
 * One command-only rule — see {@link CommandChannelService#appliesTo} for exactly how the four
 * who-it-applies-to fields combine. {@code customMessage} {@code null} (or blank) means "use the
 * built-in default notice".
 *
 * @param applyBelowRoleId   if set, the rule applies to members whose highest role is below this one
 * @param exemptFromRoleId   if set, members whose highest role is at or above this one are exempt
 * @param applyRoleIds       if non-empty, members holding any of these roles are covered by the rule
 * @param exemptRoleIds      members holding any of these roles are always exempt
 */
public record CommandChannelGroup(long id, long guildId, String name, String customMessage, boolean enabled,
                                   Long applyBelowRoleId, Long exemptFromRoleId,
                                   Set<Long> channelIds, Set<Long> applyRoleIds, Set<Long> exemptRoleIds) {
}
