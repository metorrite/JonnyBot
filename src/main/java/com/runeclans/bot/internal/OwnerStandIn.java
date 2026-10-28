package com.runeclans.bot.internal;

import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.discord.Shards;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;

import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The bot owner as the dashboard's actor in a server they aren't a member of, while their "lift restrictions" override runs (T-445). The
 * dashboard's routes take a {@link Member}, mostly for its id and name; this one has the owner's id and name, holds no roles and no Discord
 * permissions, and can't be acted on by role hierarchy, so a route that needs a real member's Discord rights refuses as it would for anyone
 * without them. Anything else a real member has (join date, voice state...) throws, which the admin API answers as an error.
 */
final class OwnerStandIn {
    private OwnerStandIn() {}

    static Member of(Guild guild, long userId) {
        String name = nameOf(guild, userId);
        return (Member) Proxy.newProxyInstance(Member.class.getClassLoader(), new Class<?>[]{Member.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getIdLong" -> userId;
            case "getId" -> Long.toUnsignedString(userId);
            case "getGuild" -> guild;
            case "getJDA" -> guild.getJDA();
            case "getEffectiveName", "getNickname" -> method.getName().equals("getNickname") ? null : name;
            case "getEffectiveAvatarUrl", "getAvatarUrl" -> {
                User user = Shards.user(guild.getJDA(), userId);
                yield user == null ? null : user.getEffectiveAvatarUrl();
            }
            case "getUser" -> {
                User user = Shards.user(guild.getJDA(), userId);
                if (user != null) yield user;
                try {
                    // a time limit, as everywhere inside a web request: the website gives up after 8 seconds
                    yield guild.getJDA().retrieveUserById(userId).timeout(DiscordLookups.BUDGET.toMillis(), TimeUnit.MILLISECONDS).complete();
                } catch (RuntimeException e) {
                    throw new DiscordLookups.Slow();
                }
            }
            case "getAsMention" -> "<@" + Long.toUnsignedString(userId) + ">";
            case "getRoles" -> List.of();
            case "getPermissions", "getPermissionsExplicit" -> EnumSet.noneOf(Permission.class);
            case "hasPermission", "canInteract", "isOwner", "hasAccess", "canSync", "isPending", "isTimedOut", "isBoosting" -> false;
            case "equals" -> proxy == args[0];
            case "hashCode" -> Long.hashCode(userId);
            case "toString" -> "OwnerStandIn(" + userId + ")";
            default -> throw new UnsupportedOperationException("The bot owner isn't a member of this server, so " + method.getName() + " can't be answered.");
        });
    }

    private static String nameOf(Guild guild, long userId) {
        String name = DiscordLookups.userName(guild.getJDA(), userId);
        return name == null || name.isBlank() ? "The bot owner" : name;
    }
}
