package com.runeclans.bot.leagues;

import com.runeclans.bot.alerts.AutoPostRouter;
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

/** Posts one league message in the channel a server picked (T-760): an outbox job, so it never pings anyone and never runs twice by itself. */
@BService
public class LeaguePostJobs implements DiscordJobHandler {
    public static final String KIND = "league.post";
    private static final EnumSet<Message.MentionType> NO_PINGS = EnumSet.noneOf(Message.MentionType.class);
    static final int MAX_TEXT = 1900;

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
        if (AutoPostRouter.skip("A league post")) return DataObject.empty().put("skipped", "dev");
        Guild guild = DiscordJobs.guild(jda, job);
        return post(guild, job.payload());
    }

    DataObject post(Guild guild, DataObject payload) {
        String channelId = payload.getString("channelId", "");
        String text = payload.getString("text", "").strip();
        if (!channelId.matches("\\d{15,20}") || text.isEmpty()) throw new DiscordJobs.Refused("Nothing to post.");
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new DiscordJobs.Refused("The league channel no longer exists.");
        if (!channel.canTalk()) throw new DiscordJobs.Refused("The bot can't post in #" + channel.getName() + ".");
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
        Message sent = DiscordJobs.send(channel.sendMessage(text).setAllowedMentions(NO_PINGS));
        return DataObject.empty().put("messageId", sent.getId());
    }
}
