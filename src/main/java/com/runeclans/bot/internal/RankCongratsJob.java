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
 * Posts the rank-up advisor's weekly congrats message (T-594, Drake's answer: the members due last week who got their rank), queued by
 * {@link SiteRankAdvisor#sweep} when the site's owner turned auto post on. A message over Discord's limit goes out as several posts, cut at
 * line ends. The text is the site owner's, so it allows no mentions at all. Not idempotent: a second run would post it twice, so the outbox
 * never repeats it after a fault that leaves it unclear whether Discord took it.
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
        Message first = null;
        for (String part : RankAdvisorRules.chunks(text, RankAdvisorRules.DISCORD_MAX)) {
            Message sent = DiscordJobs.send(channel.sendMessage(part).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)));
            if (first == null) first = sent;
        }
        if (first == null) throw new DiscordJobs.Refused("The congrats message is empty.");
        return DataObject.empty().put("messageId", first.getId()).put("channelId", channel.getId()).put("jumpUrl", first.getJumpUrl());
    }
}
