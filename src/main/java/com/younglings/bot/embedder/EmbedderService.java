package com.younglings.bot.embedder;

import com.younglings.bot.discord.jobs.DiscordJobs;
import com.younglings.bot.goodbye.GoodbyeService;
import com.younglings.bot.welcome.WelcomeConfig;
import com.younglings.bot.welcome.WelcomeMessageBuilder;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

/**
 * Posts, edits and deletes the messages composed on the Message embedder page. The message is a {@link WelcomeConfig} (the shared
 * message editor's shape) built by {@link WelcomeMessageBuilder} with nobody pinged. Every action is judged against the acting admin's own
 * permissions in the channel, like {@code /announce}, when the website asks ({@link #checkedChannel}, {@link #mayDeleteIn}; a refusal is an
 * {@link IllegalStateException} with a reason fit to show them). The Discord part then runs as a Discord job (T-456, {@code EmbedderJobs}):
 * {@link #post}, {@link #edit} and {@link #delete} send through {@link DiscordJobs#send} and refuse with {@link DiscordJobs.Refused}.
 */
@BService
public class EmbedderService {
    /** A stored list longer than this is a sign of misuse, and the page would be unusable. */
    public static final int MAX_MESSAGES_PER_GUILD = 100;

    /** Who the message is "from", for the editor's {user} placeholders: kept with the job, so running it later needs no lookup. */
    public record Author(String name, String avatarUrl) {
        public static Author of(Member member) {
            return new Author(member.getUser().getName(), member.getUser().getEffectiveAvatarUrl());
        }
    }

    /** Discord doesn't let a message change between container and classic once posted. */
    public static boolean changesKind(WelcomeConfig.MessageType before, WelcomeConfig.MessageType after) {
        return (before == WelcomeConfig.MessageType.CONTAINER) != (after == WelcomeConfig.MessageType.CONTAINER);
    }

    /** Posts {@code config} in the channel and returns the new Discord message id. */
    public long post(Guild guild, Author author, WelcomeConfig config, long channelId) {
        GuildMessageChannel channel = botChannel(guild, channelId);
        return DiscordJobs.send(channel.sendMessage(build(guild, author, config, channel))).getIdLong();
    }

    /** Edits the posted message in place (the website already refused a switch Discord can't do, see {@link #changesKind}). */
    public void edit(Guild guild, Author author, WelcomeConfig after, long channelId, long messageId) {
        GuildMessageChannel channel = botChannel(guild, channelId);
        try {
            DiscordJobs.send(channel.editMessageById(messageId, MessageEditData.fromCreateData(build(guild, author, after, channel))));
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) {
                throw new DiscordJobs.Refused("That message no longer exists in #" + channel.getName() + ". Delete this entry, or clone it to post it again.");
            }
            throw e;
        }
    }

    /** Deletes the Discord message; one that is already gone counts as deleted. A vanished channel is fine too. */
    public void delete(Guild guild, long channelId, long messageId) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) return;
        try {
            DiscordJobs.send(channel.deleteMessageById(messageId));
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) throw e;
        }
    }

    /** Whether the admin may have the bot delete a message in that channel: false when the channel is gone (nothing to delete there). */
    public boolean mayDeleteIn(Guild guild, Member actor, long channelId) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) return false;
        if (!actor.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)) {
            throw new IllegalStateException("You can't post in #" + channel.getName() + " yourself, so the bot won't delete messages there for you.");
        }
        return true;
    }

    /** The channel, if the bot can post there and so can the admin asking. Checked before a post, edit or clone is queued. */
    public GuildMessageChannel checkedChannel(Guild guild, Member actor, long channelId) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new IllegalStateException("That channel isn't a channel the bot can post in.");
        if (!channel.canTalk()) throw new IllegalStateException("The bot can't post in #" + channel.getName() + ". It needs permission to view the channel and send messages there.");
        if (!actor.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)) {
            throw new IllegalStateException("You can't post in #" + channel.getName() + " yourself, so the bot won't post there for you.");
        }
        return channel;
    }

    private static MessageCreateData build(Guild guild, Author author, WelcomeConfig config, GuildMessageChannel channel) {
        var lookup = GoodbyeService.lookup(author.name(), author.avatarUrl(), guild.getName(), channel.getName(), guild.getMemberCount());
        return WelcomeMessageBuilder.buildSilent(config, lookup);
    }

    /** The channel at the time the job runs: it may have gone, or the bot may have lost its permission since the website asked. */
    private static GuildMessageChannel botChannel(Guild guild, long channelId) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new DiscordJobs.Refused("That channel no longer exists.");
        if (!channel.canTalk()) throw new DiscordJobs.Refused("The bot can't post in #" + channel.getName() + " any more.");
        return channel;
    }
}
