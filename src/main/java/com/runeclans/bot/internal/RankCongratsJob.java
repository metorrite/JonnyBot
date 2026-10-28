package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.EnumSet;

/**
 * Posts the rank-up advisor's optional congrats message (T-594) when staff tick a member off on the Clan health page. The text is the site
 * owner's, so it allows no mentions at all. Not idempotent: a second run would post it twice, so the outbox never repeats it after a fault that
 * leaves it unclear whether Discord took it.
 */
@BService
public class RankCongratsJob implements DiscordJobHandler {
    static final String KIND = "rankup.congrats";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    static DataObject payload(long channelId, String text) {
        return DataObject.empty().put("channelId", channelId).put("text", text);
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, job.payload().getLong("channelId"));
        if (channel == null) throw new DiscordJobs.Refused("The congrats channel no longer exists. Pick another on the Clan health page.");
        if (!channel.canTalk()) throw new DiscordJobs.Refused("The bot can't post in #" + channel.getName() + " any more.");
        String text = job.payload().getString("text", "");
        if (text.isBlank()) throw new DiscordJobs.Refused("The congrats message is empty.");
        Message sent = DiscordJobs.send(channel.sendMessage(text).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)));
        return DataObject.empty().put("messageId", sent.getId()).put("channelId", channel.getId()).put("jumpUrl", sent.getJumpUrl());
    }
}
