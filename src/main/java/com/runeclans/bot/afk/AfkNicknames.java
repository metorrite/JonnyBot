package com.runeclans.bot.afk;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

/**
 * The optional "[AFK] " tag on a member's nickname while they are away. Off unless a server turns it on. It only ever adds or removes its own prefix, and it
 * quietly does nothing for a member JonnyBot may not rename (the owner, or someone with a role above JonnyBot's) or when it lacks Manage Nicknames.
 */
public final class AfkNicknames {
    public static final String PREFIX = "[AFK] ";
    private static final int MAX_NICK = 32;

    private AfkNicknames() {}

    public static boolean isTagged(String nickname) {
        return nickname != null && nickname.startsWith(PREFIX);
    }

    /** {@code name} with the tag in front, cut to fit Discord's 32 characters; unchanged when it already has it. */
    public static String tag(String name) {
        if (isTagged(name)) return name;
        String base = name.length() > MAX_NICK - PREFIX.length() ? name.substring(0, MAX_NICK - PREFIX.length()) : name;
        return PREFIX + base;
    }

    /** The nickname without the tag; null (no nickname at all) when nothing is left, or when the tag wasn't there to begin with. */
    public static String untag(String nickname) {
        if (!isTagged(nickname)) return null;
        String rest = nickname.substring(PREFIX.length()).strip();
        return rest.isEmpty() ? null : rest;
    }

    private static boolean mayRename(Guild guild, Member member) {
        return guild.getSelfMember().hasPermission(Permission.NICKNAME_MANAGE) && guild.getSelfMember().canInteract(member);
    }

    /** Puts the tag on the member's display name. */
    public static void markAway(Guild guild, Member member) {
        if (!mayRename(guild, member)) return;
        String current = member.getNickname() != null ? member.getNickname() : member.getEffectiveName();
        if (isTagged(current)) return;
        member.modifyNickname(tag(current)).reason("Away (/afk)").queue(ok -> { }, error -> { });
    }

    /** Takes the tag off again, only if it is there. */
    public static void markBack(Guild guild, Member member) {
        if (!isTagged(member.getNickname()) || !mayRename(guild, member)) return;
        member.modifyNickname(untag(member.getNickname())).reason("Back from away").queue(ok -> { }, error -> { });
    }
}
