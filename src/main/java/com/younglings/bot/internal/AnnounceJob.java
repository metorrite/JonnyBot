package com.younglings.bot.internal;

import com.younglings.bot.discord.jobs.DiscordJob;
import com.younglings.bot.discord.jobs.DiscordJobHandler;
import com.younglings.bot.discord.jobs.DiscordJobs;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Posts a dashboard announcement ({@code announce/send}) in the background. Every rule (the channel, the admin's own right to post there, which pings
 * they may make) was checked when it was queued, and the payload carries the result: the text and the pings allowed. Not idempotent: the outbox
 * never repeats it after a fault that leaves it unclear whether Discord took it.
 */
@BService
public class AnnounceJob implements DiscordJobHandler {
    static final String KIND = "announce.post";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    /** What the job needs: the channel, the finished text, and the mentions it may ping. */
    static DataObject payload(long channelId, String content, EnumSet<Message.MentionType> parse, List<Long> roleIds) {
        DataArray types = DataArray.empty();
        parse.forEach(t -> types.add(t.name()));
        DataArray roles = DataArray.empty();
        roleIds.forEach(id -> roles.add(Long.toString(id)));
        return DataObject.empty().put("channelId", Long.toString(channelId)).put("content", content).put("parse", types).put("roleIds", roles);
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        DataObject p = job.payload();
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, p.getLong("channelId"));
        if (channel == null) throw new DiscordJobs.Refused("That channel no longer exists.");
        if (!channel.canTalk()) throw new DiscordJobs.Refused("The bot can't post in #" + channel.getName() + " any more.");
        EnumSet<Message.MentionType> parse = EnumSet.noneOf(Message.MentionType.class);
        DataArray types = p.optArray("parse").orElseGet(DataArray::empty);
        for (int i = 0; i < types.length(); i++) {
            String name = types.getString(i).toUpperCase(Locale.ROOT);
            for (Message.MentionType t : Message.MentionType.values()) if (t.name().equals(name)) parse.add(t);
        }
        DataArray roles = p.optArray("roleIds").orElseGet(DataArray::empty);
        long[] roleIds = new long[roles.length()];
        for (int i = 0; i < roles.length(); i++) roleIds[i] = Long.parseLong(roles.getString(i));
        Message sent = DiscordJobs.send(channel.sendMessage(p.getString("content")).setAllowedMentions(parse).mentionRoles(roleIds));
        return DataObject.empty().put("messageId", sent.getId()).put("channelId", channel.getId()).put("channelName", channel.getName()).put("jumpUrl", sent.getJumpUrl());
    }
}
