package com.younglings.bot.clansite;

import com.younglings.bot.clansite.SiteDiscordSettings.OnlineMode;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.attribute.IAgeRestrictedChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The privacy rules of Discord on a clan website (T-365, design section 11), kept apart from HTTP so they can be tested on their own:
 * which channels may feed a public page, and what the online widget may show in each mode. Nothing here returns a Discord user id.
 */
public final class SiteDiscordRules {
    /** At most this many names in one online answer; the rest are counted. */
    public static final int MAX_NAMED = 200;
    /** At most this many posts in one feed answer. */
    public static final int MAX_POSTS = 25;

    private SiteDiscordRules() {}

    /** Why a channel can't feed a public website, or null when it can. A refusal the admin can't fix by giving the bot access is {@code forbidden}. */
    public record Refusal(boolean forbidden, String reason) {}

    public static Refusal channelRefusal(Guild guild, GuildChannel channel) {
        if (channel == null) return new Refusal(false, "That channel doesn't exist in this server.");
        if (channel instanceof ThreadChannel) return new Refusal(true, "Threads can't be shown on the website. Pick the channel itself.");
        if (channel instanceof IAgeRestrictedChannel aged && aged.isNSFW()) {
            return new Refusal(true, "#" + channel.getName() + " is age-restricted, so it can't be shown on the website.");
        }
        if (!guild.getPublicRole().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)) {
            return new Refusal(true, "#" + channel.getName() + " is private (@everyone can't read its history), so it can't be shown on the website.");
        }
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)) {
            return new Refusal(false, "The bot can't read #" + channel.getName() + ". Give it View Channel and Read Message History there.");
        }
        return null;
    }

    // ---------- who's online ----------

    /** The display group a member falls under: their highest hoisted role, or the trailing "Online" group. */
    public record Group(long key, String name, int colorRaw, int position) {
        static final Group ONLINE = new Group(0, "Online", -1, Integer.MIN_VALUE);
    }

    /** One online member as the rules need them; {@code userId} is only used to look up the hidden list and never leaves the bot. */
    public record Person(long userId, boolean bot, OnlineStatus status, Group group, String displayName, int colorRaw, String avatarUrl) {}

    public static Person person(Member member) {
        Role hoisted = member.getRoles().stream().filter(Role::isHoisted).findFirst().orElse(null);
        Group group = hoisted == null ? Group.ONLINE
                : new Group(hoisted.getIdLong(), hoisted.getName(), hoisted.getColors().getPrimaryRaw(), hoisted.getPosition());
        return new Person(member.getIdLong(), member.getUser().isBot(), member.getOnlineStatus(), group, member.getEffectiveName(),
                member.getColors().getPrimaryRaw(), member.getEffectiveAvatarUrl());
    }

    /** Whether Discord shows this member as online. Bots never count; invisible members already reach the bot as offline. */
    static boolean online(Person p) {
        if (p.bot()) return false;
        return p.status() == OnlineStatus.ONLINE || p.status() == OnlineStatus.IDLE || p.status() == OnlineStatus.DO_NOT_DISTURB;
    }

    /**
     * The online answer for a mode other than off: {@code count} gives the number only; {@code names} adds display names grouped by hoisted
     * role (groups by role position, names alphabetical); {@code names+avatars} adds avatars. Hidden members are counted but never named,
     * and they don't show inside a group either.
     */
    public static DataObject online(List<Person> people, OnlineMode mode, Set<Long> hidden) {
        List<Person> online = people.stream().filter(SiteDiscordRules::online).toList();
        DataObject answer = DataObject.empty().put("mode", mode.key()).put("unavailable", false).put("total", online.size());
        if (mode == OnlineMode.COUNT || mode == OnlineMode.OFF) return answer;

        List<Person> named = new ArrayList<>(online.stream().filter(p -> !hidden.contains(p.userId())).toList());
        named.sort(Comparator.comparing((Person p) -> p.group().position()).reversed()
                .thenComparing(p -> p.displayName().toLowerCase()));
        if (named.size() > MAX_NAMED) named = named.subList(0, MAX_NAMED);

        Map<Long, List<Person>> byGroup = new LinkedHashMap<>();
        Map<Long, Group> groups = new LinkedHashMap<>();
        for (Person p : named) {
            groups.putIfAbsent(p.group().key(), p.group());
            byGroup.computeIfAbsent(p.group().key(), k -> new ArrayList<>()).add(p);
        }

        DataArray groupArray = DataArray.empty();
        for (Group group : groups.values()) {
            DataArray members = DataArray.empty();
            for (Person p : byGroup.get(group.key())) {
                DataObject m = DataObject.empty().put("displayName", p.displayName()).put("status", p.status().getKey()).put("colorRaw", p.colorRaw());
                if (mode == OnlineMode.NAMES_AVATARS) m.put("avatarUrl", p.avatarUrl() == null ? null : p.avatarUrl() + "?size=64");
                members.add(m);
            }
            groupArray.add(DataObject.empty().put("name", group.name()).put("colorRaw", group.colorRaw()).put("count", members.length()).put("members", members));
        }
        return answer.put("named", named.size()).put("groups", groupArray);
    }

    /** The online answer while the Presence intent is off: nothing can be known, which is not the same as nobody being online. */
    public static DataObject onlineUnavailable(OnlineMode mode) {
        return DataObject.empty().put("mode", mode.key()).put("unavailable", true).put("total", 0);
    }
}
