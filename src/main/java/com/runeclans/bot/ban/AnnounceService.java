package com.runeclans.bot.ban;

import com.runeclans.bot.ban.AnnouncePolicy.Plan;
import com.runeclans.bot.ban.AnnouncePolicy.Ping;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

/** Posts an announcement for a staff member, by {@code /announce} or from the dashboard. The mention rules live in {@link AnnouncePolicy}. */
@BService
public class AnnounceService {
    /** Where an announcement goes and what it says, checked: what {@link #send} posts, or what the dashboard queues for the bot to post later. */
    public record Prepared(GuildMessageChannel channel, Plan plan) {}

    /** Posts the announcement, or throws {@link IllegalStateException} with a reason fit to show the sender. */
    public String send(Guild guild, Member sender, long channelId, String message, boolean everyone, boolean here, Long roleId) {
        Prepared prepared = prepare(guild, sender, channelId, message, everyone, here, roleId);
        Plan plan = prepared.plan();
        prepared.channel().sendMessage(plan.content())
                .setAllowedMentions(plan.parse())
                .mentionRoles(plan.roleIds().stream().mapToLong(Long::longValue).toArray())
                .complete();
        return prepared.channel().getName();
    }

    /**
     * Every check {@link #send} makes, without talking to Discord: the channel, the sender's own right to post there, and the pings they may make.
     * Throws {@link IllegalStateException} with a reason fit to show the sender.
     */
    public Prepared prepare(Guild guild, Member sender, long channelId, String message, boolean everyone, boolean here, Long roleId) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new IllegalStateException("That channel isn't a channel the bot can post in.");
        if (!channel.canTalk()) throw new IllegalStateException("The bot can't post in #" + channel.getName() + ". It needs permission to view the channel and send messages there.");
        if (!sender.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)) {
            throw new IllegalStateException("You can't post in #" + channel.getName() + " yourself, so the bot won't post there for you.");
        }

        Role role = roleId == null ? null : guild.getRoleById(roleId);
        if (roleId != null && role == null) throw new IllegalStateException("That role doesn't exist in this server.");
        Ping ping = AnnouncePolicy.choose(everyone, here, role != null);
        boolean mayMentionEveryone = sender.hasPermission(channel, Permission.MESSAGE_MENTION_EVERYONE);
        Plan plan = AnnouncePolicy.plan(message, ping, roleId, role != null && role.isMentionable(), mayMentionEveryone);
        if (!plan.ok()) throw new IllegalStateException(plan.refusal());
        return new Prepared(channel, plan);
    }
}
