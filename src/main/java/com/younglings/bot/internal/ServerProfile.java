package com.younglings.bot.internal;

import com.younglings.bot.discord.DiscordLookups;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A member's own look in one server, as the website changes it: the nickname and the name-colour role. Shared by the home site's
 * {@code /internal/member}, {@code /internal/nickname} and {@code /internal/color-role} ({@link InternalApiServer}) and a clan site's
 * {@code sites/{slug}/me/server-profile|nickname|color-role} ({@link DirectoryClanSiteMemberRoutes}, T-369), so the rules are the same in
 * both. The user id always comes from the caller's verified login session; every call acts on that one member in the one server given.
 */
final class ServerProfile {
    static final int MAX_NICKNAME_LENGTH = 32; // Discord's own limit

    private ServerProfile() {}

    /** A refusal the website shows as it is. */
    static final class Refused extends RuntimeException {
        final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /**
     * Cache-first, then a one-off REST fetch on a miss: the bot caches only online members, and these calls are rare website actions, so
     * the occasional extra round trip is fine. {@code null} if the user isn't in the server at all; {@link DiscordLookups.Slow} (a 503) when
     * Discord didn't answer in time.
     */
    static Member resolveMember(Guild guild, long userId) {
        return DiscordLookups.memberOrSlow(guild, userId);
    }

    /** The member's names, avatar and current colour key. */
    static DataObject info(Member member) {
        String colorRoleKey = null;
        for (Role role : member.getRoles()) {
            String key = ColorRoleCatalog.keyFromRoleName(role.getName());
            if (key != null) {
                colorRoleKey = key;
                break;
            }
        }
        return DataObject.empty()
                .put("id", member.getId())
                .put("username", member.getUser().getName())
                .put("nickname", member.getNickname())
                .put("displayName", member.getEffectiveName())
                .put("avatarUrl", member.getEffectiveAvatarUrl())
                .put("colorRole", colorRoleKey);
    }

    /** Sets (or with a blank or null {@code nickname}, clears) the member's nickname. */
    static DataObject setNickname(Member member, DataObject body) {
        String nickname = body.isNull("nickname") ? null : body.getString("nickname").trim();
        if (nickname != null && nickname.isEmpty()) nickname = null;
        if (nickname != null && nickname.length() > MAX_NICKNAME_LENGTH) {
            throw new Refused(400, "Nickname must be " + MAX_NICKNAME_LENGTH + " characters or fewer");
        }
        member.modifyNickname(nickname).complete();
        return DataObject.empty().put("nickname", nickname);
    }

    /** Swaps the member's colour role for {@code color} (a {@link ColorRoleCatalog} key), or removes it for null; creates the role if missing. */
    static DataObject setColorRole(Guild guild, Member member, DataObject body) {
        String colorKey = body.isNull("color") ? null : body.getString("color");
        if (colorKey != null && !ColorRoleCatalog.isValidKey(colorKey)) throw new Refused(400, "Unknown color: " + colorKey);

        List<Role> toRemove = new ArrayList<>();
        for (Role role : member.getRoles()) {
            if (ColorRoleCatalog.isColorRoleName(role.getName())) toRemove.add(role);
        }
        List<Role> toAdd = new ArrayList<>();
        if (colorKey != null) toAdd.add(ensureColorRole(guild, colorKey));
        if (!toAdd.isEmpty() || !toRemove.isEmpty()) guild.modifyMemberRoles(member, toAdd, toRemove).complete();
        return DataObject.empty().put("colorRole", colorKey);
    }

    private static Role ensureColorRole(Guild guild, String colorKey) {
        String roleName = ColorRoleCatalog.roleName(colorKey);
        List<Role> existing = guild.getRolesByName(roleName, true);
        if (!existing.isEmpty()) return existing.getFirst();
        return guild.createRole()
                .setName(roleName)
                .setColor(ColorRoleCatalog.colorFor(colorKey))
                .setMentionable(false)
                .setHoisted(false)
                .complete();
    }
}
