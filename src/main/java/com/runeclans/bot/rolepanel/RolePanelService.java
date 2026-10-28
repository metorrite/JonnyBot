package com.runeclans.bot.rolepanel;

import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.internal.MemberApi;
import com.runeclans.bot.rolepanel.RolePanel.RoleButton;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Posting a role panel's message, and keeping that message in step with the panel when it is edited. The website checks with {@link #checkPost} /
 * {@link #checkAttach} (no Discord call) and queues the rest as a Discord job ({@code RolePanelJobs}, T-456); {@link #post}, {@link #attach}
 * and {@link #takeDown} wait for Discord, so they run on the outbox's lane.
 */
@BService
public class RolePanelService {
    private static final Logger log = LoggerFactory.getLogger(RolePanelService.class);
    private static final Pattern MESSAGE_LINK = Pattern.compile("https?://(?:\\w+\\.)?discord(?:app)?\\.com/channels/(\\d{1,20})/(\\d{1,20})/(\\d{1,20})/?");

    /** Why a panel couldn't be posted, in words for the person who pressed Post. */
    public static final class PostException extends RuntimeException {
        public PostException(String message) {
            super(message);
        }
    }

    /** The server, channel and message a Discord message link points at. */
    public record MessageLink(long guildId, long channelId, long messageId) {}

    /** Reads a Discord message link, or null if the text isn't one. */
    public static MessageLink parseLink(String text) {
        if (text == null) return null;
        Matcher m = MESSAGE_LINK.matcher(text.strip());
        if (!m.matches()) return null;
        return new MessageLink(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)));
    }

    private final RolePanelRepository repository;

    public RolePanelService(RolePanelRepository repository) {
        this.repository = repository;
    }

    /**
     * Posts the panel in a channel, or, if it is already posted there, edits that message. A message deleted by hand is simply posted
     * again. A reactions panel gets its reactions added to the message. Returns the panel as it now stands.
     */
    public RolePanel post(Guild guild, RolePanel panel, long channelId) {
        GuildMessageChannel channel = checkPost(guild, panel, channelId);
        MessageCreateData data = RolePanelMessage.build(panel, id -> roleName(guild, id));
        try {
            if (panel.posted() && panel.channelId() == channelId && !panel.attached()) {
                try {
                    MessageEditBuilder edit = new MessageEditBuilder().setContent("").setEmbeds(data.getEmbeds()).setComponents(data.getComponents());
                    Message edited = DiscordJobs.send(channel.editMessageById(panel.messageId(), edit.build()));
                    // the style may have changed, so whatever reactions are on it from before go
                    if (guild.getSelfMember().hasPermission(channel, Permission.MESSAGE_MANAGE)) edited.clearReactions().complete();
                    addReactions(channel, edited.getIdLong(), panel);
                    repository.setPosted(guild.getIdLong(), panel.id(), channelId, edited.getIdLong());
                    return repository.get(guild.getIdLong(), panel.id()).orElse(panel);
                } catch (DiscordJobs.RetryLater wait) {
                    throw wait; // nothing changed yet: the outbox tries again when Discord allows
                } catch (RuntimeException gone) {
                    log.info("Role panel {}'s message is gone; posting it again", panel.id());
                }
            }
            Message sent = DiscordJobs.send(channel.sendMessage(data));
            // after this point a retry would post a second message, so the reactions wait out their rate limit here instead
            addReactions(channel, sent.getIdLong(), panel);
            repository.setPosted(guild.getIdLong(), panel.id(), channelId, sent.getIdLong());
            return repository.get(guild.getIdLong(), panel.id()).orElse(panel);
        } catch (PostException | DiscordJobs.RetryLater e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Couldn't post role panel {} in channel {}", panel.id(), channelId, e);
            throw new PostException("Discord refused the message. Check the bot can post, embed links and use external emojis in #" + channel.getName() + ".");
        }
    }

    /**
     * Puts a reactions panel on a message that already exists (found by its link): JonnyBot adds the panel's reactions to it and from then on
     * hands out the roles for them. The message itself is never edited or deleted.
     */
    public RolePanel attach(Guild guild, RolePanel panel, String link) {
        MessageLink parsed = checkAttach(guild, panel, link);
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, parsed.channelId());
        try {
            DiscordJobs.send(channel.retrieveMessageById(parsed.messageId()));
        } catch (DiscordJobs.RetryLater wait) {
            throw wait;
        } catch (RuntimeException e) {
            throw new PostException("The bot can't find that message. Check the link and that it hasn't been deleted.");
        }
        try {
            addReactions(channel, parsed.messageId(), panel);
        } catch (RuntimeException e) {
            log.warn("Couldn't add the reactions of role panel {} to message {}", panel.id(), parsed.messageId(), e);
            throw new PostException("Discord refused the reactions. Check the bot can add reactions and use external emojis in #" + channel.getName() + ".");
        }
        repository.setPosted(guild.getIdLong(), panel.id(), parsed.channelId(), parsed.messageId(), true);
        return repository.get(guild.getIdLong(), panel.id()).orElse(panel);
    }

    /** What taking a panel's message down needs once the panel itself is deleted: where the message is, and which of the bot's reactions to remove. */
    public record TakeDown(long channelId, long messageId, boolean attached, List<String> emojis) {
        /** For a posted panel; null when there is nothing on Discord to take down. */
        public static TakeDown of(RolePanel panel) {
            if (!panel.posted()) return null;
            return new TakeDown(panel.channelId(), panel.messageId(), panel.attached(), panel.buttons().stream().map(RoleButton::emoji).filter(e -> e != null && !e.isBlank()).toList());
        }
    }

    /**
     * Takes a panel's message down when the panel is deleted: its own message is deleted, a message it was attached to keeps everything but the bot's
     * own reactions, which are removed. A message or channel that is already gone counts as done.
     */
    public void takeDown(Guild guild, TakeDown what) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, what.channelId());
        if (channel == null) return;
        try {
            if (!what.attached()) {
                DiscordJobs.send(channel.deleteMessageById(what.messageId()));
                return;
            }
            for (String raw : what.emojis()) {
                Emoji emoji = RolePanelMessage.emoji(raw);
                // removing a reaction twice is harmless, so these may wait out their own rate limit
                if (emoji != null) channel.removeReactionById(what.messageId(), emoji).complete();
            }
        } catch (net.dv8tion.jda.api.exceptions.ErrorResponseException e) {
            if (e.getErrorResponse() != net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MESSAGE) throw e;
        }
    }

    /** Every check {@link #post} makes before it talks to Discord: the channel, the bot's permissions there, and the panel itself. */
    public static GuildMessageChannel checkPost(Guild guild, RolePanel panel, long channelId) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new PostException("That channel doesn't exist in this server.");
        if (!channel.canTalk()) throw new PostException("The bot can't post in #" + channel.getName() + ". It needs permission to view the channel and send messages there.");
        if (panel.buttons().isEmpty()) throw new PostException("Add at least one role first.");
        requireFits(panel);
        if (panel.style() == RolePanel.Style.REACTIONS) requireReactionPermission(channel);
        return channel;
    }

    /** Every check {@link #attach} makes before it talks to Discord. Answers where the message is. */
    public MessageLink checkAttach(Guild guild, RolePanel panel, String link) {
        if (panel.style() != RolePanel.Style.REACTIONS) throw new PostException("Only a reactions panel can be put on an existing message.");
        if (panel.buttons().isEmpty()) throw new PostException("Add at least one role first.");
        requireFits(panel);
        MessageLink parsed = parseLink(link);
        if (parsed == null) throw new PostException("That isn't a Discord message link. Right-click the message and choose Copy Message Link.");
        if (parsed.guildId() != guild.getIdLong()) throw new PostException("That message is in a different server.");
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, parsed.channelId());
        if (channel == null) throw new PostException("That channel doesn't exist in this server.");
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)) {
            throw new PostException("The bot can't read #" + channel.getName() + ". It needs View Channel and Read Message History there.");
        }
        requireReactionPermission(channel);
        if (repository.byMessage(guild.getIdLong(), parsed.messageId()).filter(p -> !p.id().equals(panel.id())).isPresent()) {
            throw new PostException("Another role panel is already on that message.");
        }
        return parsed;
    }

    private static void addReactions(GuildMessageChannel channel, long messageId, RolePanel panel) {
        if (panel.style() != RolePanel.Style.REACTIONS) return;
        for (RoleButton b : panel.buttons()) {
            Emoji emoji = RolePanelMessage.emoji(b.emoji());
            if (emoji != null) channel.addReactionById(messageId, emoji).complete();
        }
    }

    private static void requireFits(RolePanel panel) {
        if (panel.buttons().size() > panel.style().maxItems()) {
            throw new PostException("A " + panel.style().name().toLowerCase() + " panel can offer at most " + panel.style().maxItems() + " roles.");
        }
    }

    private static void requireReactionPermission(GuildMessageChannel channel) {
        if (!channel.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_ADD_REACTION, Permission.MESSAGE_HISTORY)) {
            throw new PostException("The bot needs the Add Reactions and Read Message History permissions in #" + channel.getName() + " for a reactions panel.");
        }
    }

    private static String roleName(Guild guild, long id) {
        Role role = guild.getRoleById(id);
        return role == null ? "Role" : role.getName();
    }

    /** Whether the bot may hand this role out at all; the same bar every automatic role in the bot is held to. */
    public static boolean canGive(Guild guild, Role role) {
        return MemberApi.safeToAssign(guild, role);
    }
}
