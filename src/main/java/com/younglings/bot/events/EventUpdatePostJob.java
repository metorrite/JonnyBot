package com.younglings.bot.events;

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

/**
 * Posts one board update (T-650): a daily recap or a milestone, through the Discord outbox. The text carries names, never mentions, and the message
 * allows none. Not idempotent: a second run would post it twice, so the outbox never repeats it after an unclear failure.
 */
@BService
public class EventUpdatePostJob implements DiscordJobHandler {
    public static final String KIND = "event.update.post";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    public static DataObject payload(long eventId, long channelId, String key, String text) {
        return DataObject.empty().put("eventId", eventId).put("channelId", String.valueOf(channelId)).put("key", key).put("text", text);
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        long channelId = Long.parseLong(job.payload().getString("channelId"));
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new DiscordJobs.Refused("That channel no longer exists.");
        if (!channel.canTalk()) throw new DiscordJobs.Refused("The bot can't post in #" + channel.getName() + ".");
        Message sent = DiscordJobs.send(channel.sendMessage(job.payload().getString("text", "")).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)));
        return DataObject.empty().put("messageId", sent.getId()).put("channelId", channel.getId());
    }
}
