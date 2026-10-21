package com.younglings.bot.servertag;

import com.younglings.bot.moderation.ModAction;
import com.younglings.bot.moderation.ModDurations;

import java.util.ArrayList;
import java.util.List;

/**
 * A server's server-tag moderation: members whose Discord profile shows the server tag (the "primary server") of a flagged server are
 * reported or acted on. Off until switched on, and the action starts as ALERT, which only posts a note and touches nobody.
 *
 * @param flaggedGuildIds the ids of the servers whose tag is flagged
 * @param alertChannelId  where the note goes, or null
 * @param exemptRoleIds   holders of these roles are never acted on
 */
public record ServerTagConfig(long guildId, boolean enabled, List<Long> flaggedGuildIds, Action action, int timeoutMinutes, Long alertChannelId, List<Long> exemptRoleIds) {
    public static final int MAX_FLAGGED = 50;
    public static final int MAX_EXEMPT_ROLES = 25;

    public enum Action {
        ALERT(null), TIMEOUT(ModAction.TIMEOUT), KICK(ModAction.KICK), BAN(ModAction.BAN);

        /** What is done through the moderation system, or null for the alert-only action. */
        public final ModAction modAction;

        Action(ModAction modAction) {
            this.modAction = modAction;
        }
    }

    public static ServerTagConfig defaults(long guildId) {
        return new ServerTagConfig(guildId, false, List.of(), Action.ALERT, 24 * 60, null, List.of());
    }

    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (enabled && flaggedGuildIds.isEmpty()) problems.add("Add at least one flagged server before switching this on.");
        if (flaggedGuildIds.size() > MAX_FLAGGED) problems.add("At most " + MAX_FLAGGED + " flagged servers.");
        if (flaggedGuildIds.contains(guildId)) problems.add("Your own server can't be flagged.");
        if (action == Action.TIMEOUT && (timeoutMinutes < 1 || timeoutMinutes > ModDurations.MAX_TIMEOUT_MINUTES)) problems.add("A timeout must last between 1 minute and 28 days.");
        if (enabled && action == Action.ALERT && alertChannelId == null) problems.add("Alert only needs an alert channel, or nobody would ever hear about it.");
        if (exemptRoleIds.size() > MAX_EXEMPT_ROLES) problems.add("At most " + MAX_EXEMPT_ROLES + " exempt roles.");
        return problems;
    }
}
