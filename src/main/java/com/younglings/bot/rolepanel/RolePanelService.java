package com.younglings.bot.rolepanel;

import com.younglings.bot.internal.MemberApi;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Posting a role panel's message, and keeping that message in step with the panel when it is edited. */
@BService
public class RolePanelService {
    private static final Logger log = LoggerFactory.getLogger(RolePanelService.class);

    /** Why a panel couldn't be posted, in words for the person who pressed Post. */
    public static final class PostException extends RuntimeException {
        public PostException(String message) {
            super(message);
        }
    }

    private final RolePanelRepository repository;

    public RolePanelService(RolePanelRepository repository) {
        this.repository = repository;
    }

    /**
     * Posts the panel in a channel, or, if it is already posted there, edits that message. A message deleted by hand is simply posted
     * again. Returns the panel as it now stands.
     */
    public RolePanel post(Guild guild, RolePanel panel, long channelId) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new PostException("That channel doesn't exist in this server.");
        if (!channel.canTalk()) throw new PostException("JonnyBot can't post in #" + channel.getName() + ". It needs permission to view the channel and send messages there.");
        if (panel.buttons().isEmpty()) throw new PostException("Add at least one role button first.");

        MessageCreateData data = RolePanelMessage.build(panel, id -> {
            Role role = guild.getRoleById(id);
            return role == null ? "Role" : role.getName();
        });
        try {
            if (panel.posted() && panel.channelId() == channelId) {
                try {
                    Message edited = channel.editMessageById(panel.messageId(), MessageEditData.fromCreateData(data)).complete();
                    repository.setPosted(guild.getIdLong(), panel.id(), channelId, edited.getIdLong());
                    return repository.get(guild.getIdLong(), panel.id()).orElse(panel);
                } catch (RuntimeException gone) {
                    log.info("Role panel {}'s message is gone; posting it again", panel.id());
                }
            }
            Message sent = channel.sendMessage(data).complete();
            repository.setPosted(guild.getIdLong(), panel.id(), channelId, sent.getIdLong());
            return repository.get(guild.getIdLong(), panel.id()).orElse(panel);
        } catch (PostException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Couldn't post role panel {} in channel {}", panel.id(), channelId, e);
            throw new PostException("Discord refused the message. Check JonnyBot can post, embed links and use external emojis in #" + channel.getName() + ".");
        }
    }

    /** Whether the bot may hand this role out at all; the same bar every automatic role in the bot is held to. */
    public static boolean canGive(Guild guild, Role role) {
        return MemberApi.safeToAssign(guild, role);
    }
}
