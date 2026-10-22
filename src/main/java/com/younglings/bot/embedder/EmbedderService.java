package com.younglings.bot.embedder;

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
 * permissions in the channel, like {@code /announce}, and a failure is an {@link IllegalStateException} with a reason fit to show them.
 */
@BService
public class EmbedderService {
    /** A stored list longer than this is a sign of misuse, and the page would be unusable. */
    public static final int MAX_MESSAGES_PER_GUILD = 100;

    /** Discord doesn't let a message change between container and classic once posted. */
    public static boolean changesKind(WelcomeConfig.MessageType before, WelcomeConfig.MessageType after) {
        return (before == WelcomeConfig.MessageType.CONTAINER) != (after == WelcomeConfig.MessageType.CONTAINER);
    }

    /** Posts {@code config} in the channel and returns the new Discord message id. */
    public long post(Guild guild, Member actor, WelcomeConfig config, long channelId) {
        GuildMessageChannel channel = checkedChannel(guild, actor, channelId);
        try {
            return channel.sendMessage(build(guild, actor, config, channel)).complete().getIdLong();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Discord wouldn't let the message be posted in #" + channel.getName() + ".");
        }
    }

    /** Edits the posted message in place. {@code before} is what it was posted as, to refuse a switch Discord can't do. */
    public void edit(Guild guild, Member actor, WelcomeConfig before, WelcomeConfig after, long channelId, long messageId) {
        if (changesKind(before.messageType(), after.messageType())) {
            throw new IllegalStateException("A posted message can't change between a Container and the other kinds. Clone it into a new message instead.");
        }
        GuildMessageChannel channel = checkedChannel(guild, actor, channelId);
        try {
            channel.editMessageById(messageId, MessageEditData.fromCreateData(build(guild, actor, after, channel))).complete();
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) {
                throw new IllegalStateException("That message no longer exists in #" + channel.getName() + ". Delete this entry, or clone it to post it again.");
            }
            throw new IllegalStateException("Discord wouldn't let the message be edited.");
        }
    }

    /** Deletes the Discord message; one that is already gone counts as deleted. A vanished channel is fine too. */
    public void delete(Guild guild, Member actor, long channelId, long messageId) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) return;
        if (!actor.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)) {
            throw new IllegalStateException("You can't post in #" + channel.getName() + " yourself, so JonnyBot won't delete messages there for you.");
        }
        try {
            channel.deleteMessageById(messageId).complete();
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) throw new IllegalStateException("Discord wouldn't let the message be deleted.");
        }
    }

    private MessageCreateData build(Guild guild, Member actor, WelcomeConfig config, GuildMessageChannel channel) {
        var lookup = GoodbyeService.lookup(actor.getUser().getName(), actor.getUser().getEffectiveAvatarUrl(), guild.getName(), channel.getName(), guild.getMemberCount());
        return WelcomeMessageBuilder.buildSilent(config, lookup);
    }

    private GuildMessageChannel checkedChannel(Guild guild, Member actor, long channelId) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new IllegalStateException("That channel isn't a channel JonnyBot can post in.");
        if (!channel.canTalk()) throw new IllegalStateException("JonnyBot can't post in #" + channel.getName() + ". It needs permission to view the channel and send messages there.");
        if (!actor.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)) {
            throw new IllegalStateException("You can't post in #" + channel.getName() + " yourself, so JonnyBot won't post there for you.");
        }
        return channel;
    }
}
