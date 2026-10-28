package com.runeclans.bot.honeypot;

import com.runeclans.bot.moderation.ModAction;
import com.runeclans.bot.moderation.ModDurations;

import java.util.ArrayList;
import java.util.List;

/**
 * A server's honeypot: a decoy channel and/or role that no real member should use. Whoever posts in the channel or takes the role gets the
 * chosen action. Off until a server switches it on; the action starts as a timeout (the one that can be undone).
 *
 * @param channelId  the decoy channel, or null
 * @param roleId     the decoy role, or null
 * @param action     BAN, KICK or TIMEOUT
 * @param exemptRoleIds holders of these roles are never acted on (staff, testers)
 * @param deleteDays for BAN, days of their messages to delete, 0 to 7
 */
public record HoneypotConfig(long guildId, boolean enabled, Long channelId, Long roleId, Action action, int timeoutMinutes, List<Long> exemptRoleIds, int deleteDays) {
    public static final int MAX_EXEMPT_ROLES = 25;
    public static final int DEFAULT_TIMEOUT_MINUTES = 24 * 60;

    public enum Action {
        TIMEOUT(ModAction.TIMEOUT), KICK(ModAction.KICK), BAN(ModAction.BAN);

        public final ModAction modAction;

        Action(ModAction modAction) {
            this.modAction = modAction;
        }
    }

    public static HoneypotConfig defaults(long guildId) {
        return new HoneypotConfig(guildId, false, null, null, Action.TIMEOUT, DEFAULT_TIMEOUT_MINUTES, List.of(), 0);
    }

    /** What is wrong with these settings, in words for the dashboard (empty when they can be saved). */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (enabled && channelId == null && roleId == null) problems.add("Choose a decoy channel or a decoy role before switching the honeypot on.");
        if (channelId != null && roleId != null && channelId.equals(roleId)) problems.add("The decoy channel and role can't be the same thing.");
        if (action == Action.TIMEOUT && (timeoutMinutes < 1 || timeoutMinutes > ModDurations.MAX_TIMEOUT_MINUTES)) problems.add("A timeout must last between 1 minute and 28 days.");
        if (deleteDays < 0 || deleteDays > 7) problems.add("Days of messages to delete must be between 0 and 7.");
        if (exemptRoleIds.size() > MAX_EXEMPT_ROLES) problems.add("At most " + MAX_EXEMPT_ROLES + " exempt roles.");
        return problems;
    }
}
