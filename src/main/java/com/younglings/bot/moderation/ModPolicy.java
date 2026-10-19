package com.younglings.bot.moderation;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.util.Set;

/**
 * The rules for whether a moderation action may be done to someone, in one place so they can be tested without Discord: who can never be
 * touched (yourself, the bot, the server owner, anyone holding a protected role), who outranks whom, and what JonnyBot itself is
 * allowed to do. {@link #refusal} returns the reason in words, or {@code null} when the action may go ahead. Nothing here changes anything.
 */
public final class ModPolicy {
    private ModPolicy() {}

    /**
     * @param actor        who is doing it, null for the bot itself (autopunish, automod, honeypot), which then skips the "you outrank them" rule
     * @param targetId     the person's Discord id
     * @param target       the person as a server member, null when they are not in the server (a ban of someone who left, an unban)
     * @param protectedIds role ids that can never be moderated (set on the dashboard)
     * @param actorIsAdmin whether the actor is in the Admin permission group: other admins can only be touched by admins
     * @param targetIsAdmin whether the target is in the Admin permission group
     */
    public static String refusal(ModAction action, Guild guild, Member actor, long targetId, Member target, Set<Long> protectedIds,
                                 boolean actorIsAdmin, boolean targetIsAdmin) {
        Member self = guild.getSelfMember();
        if (actor != null && actor.getIdLong() == targetId) return "You can't do that to yourself.";
        if (targetId == self.getIdLong()) return "I won't do that to myself.";
        if (guild.getOwnerIdLong() == targetId) return "The server owner can't be moderated.";

        boolean undoing = action == ModAction.UNBAN || action == ModAction.UNTIMEOUT;
        if (!undoing && target != null) {
            for (Role role : target.getRoles()) {
                if (protectedIds.contains(role.getIdLong())) return "They hold the protected role " + role.getName() + ", so they can't be moderated.";
            }
            if (targetIsAdmin && actor != null && !actorIsAdmin) return "Only an Admin can moderate another Admin.";
        }

        if (action.permission != null && !self.hasPermission(action.permission)) return "I need the " + action.permission.getName() + " permission to do that.";

        if (target != null && action != ModAction.UNBAN) {
            if (actor != null && !actor.canInteract(target)) return "Their highest role isn't below yours, so you can't do that to them.";
            if (!self.canInteract(target)) return "Their highest role isn't below mine, so I can't do that to them. Move my role higher.";
            if ((action == ModAction.TIMEOUT) && target.hasPermission(Permission.ADMINISTRATOR)) return "Discord doesn't allow timing out someone with the Administrator permission.";
        }
        return null;
    }
}
