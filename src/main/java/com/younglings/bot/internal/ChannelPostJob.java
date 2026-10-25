package com.younglings.bot.internal;

import com.younglings.bot.announcement.PostMarkup;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.discord.jobs.DiscordJob;
import com.younglings.bot.discord.jobs.DiscordJobHandler;
import com.younglings.bot.discord.jobs.DiscordJobs;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.EnumSet;
import java.util.List;

/**
 * Posts a dashboard message ({@code POST /internal/admin/post}) in the background. Not idempotent: a second run would post it twice, so the
 * outbox never repeats it after a fault that leaves it unclear whether Discord took it (only after a rate limit, when nothing was sent).
 */
@BService
public class ChannelPostJob implements DiscordJobHandler {
    static final String KIND = "channel.post";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, job.payload().getLong("channelId"));
        if (channel == null) throw new DiscordJobs.Refused("That channel no longer exists.");
        if (!channel.canTalk()) throw new DiscordJobs.Refused("The bot can't post in #" + channel.getName() + " any more.");
        PostMarkup.Parsed parsed = PostMarkup.parse(job.payload().getString("text", ""), true);
        if (parsed.hasErrors()) throw new DiscordJobs.Refused("The message has mistakes; fix them and post it again.");
        Message sent = DiscordJobs.send(channel.sendMessageComponents(List.of(parsed.toContainer(Containers.PRIMARY))).useComponentsV2(true)
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)));
        return DataObject.empty().put("messageId", sent.getId()).put("channelId", channel.getId()).put("jumpUrl", sent.getJumpUrl());
    }
}
