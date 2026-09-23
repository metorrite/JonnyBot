package com.younglings.bot.commands.teamforming;

import net.dv8tion.jda.api.entities.Guild;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retired in favor of {@code /embed}'s generic post/remove system (see
 * {@code com.younglings.bot.commands.embed}), which posts the teamforming panel via
 * {@link TeamformingService#buildPublicPanelMessage()} directly instead of through this class —
 * kept (not deleted) as reference/fallback, but no longer registered. BotCommands validates that
 * every {@code @JDASlashCommand} method's declaring class is {@code @Command} (and throws at
 * startup otherwise), so all the framework annotations are stripped here, not just the
 * class-level one — this is now plain, uncalled Java, not a disabled command. Note the
 * role-deletion half of the old {@code /teamforming revert} ({@link
 * TeamformingService#deleteAllCatalogRoles}) isn't wired into the new flow — {@code /embed remove}
 * only un-posts the message, matching its generic, embed-type-agnostic contract; role cleanup
 * would need its own tool if still needed.
 */
public class TeamformingCommand {
    private static final Logger log = LoggerFactory.getLogger(TeamformingCommand.class);

    // Matches a Discord message link's channel and message ID: .../channels/<guild>/<channel>/<message>
    private static final Pattern MESSAGE_LINK_PATTERN = Pattern.compile("channels/\\d+/(\\d+)/(\\d+)/?$");

    private final TeamformingService teamformingService;

    public TeamformingCommand(TeamformingService teamformingService) {
        this.teamformingService = teamformingService;
    }

    public void onPost(
            GuildSlashEvent event,
            TextChannel channel
    ) {
        Guild guild = event.getGuild();
        event.deferReply(true).queue();

        List<String> createdRoles = teamformingService.ensureAllRolesExist(guild);
        MessageCreateData panel = teamformingService.buildPublicPanelMessage();

        channel.sendMessage(panel).queue(
                message -> {
                    String summary = createdRoles.isEmpty()
                            ? "All teamforming roles already existed."
                            : "Created " + createdRoles.size() + " new role(s): " + String.join(", ", createdRoles) + ".";
                    event.getHook().editOriginal(
                            "Teamforming panel posted in " + channel.getAsMention() + ".\n" + summary
                    ).queue();
                },
                failure -> {
                    log.error("Failed to post teamforming panel in channel {}", channel.getIdLong(), failure);
                    event.getHook().editOriginal(
                            "Failed to post the panel — check the bot can send messages, embed links, " +
                            "and attach files in that channel."
                    ).queue();
                }
        );
    }

    public void onRevert(
            GuildSlashEvent event,
            @Nullable String panelMessage
    ) {
        Guild guild = event.getGuild();
        List<String> deletedRoles = teamformingService.deleteAllCatalogRoles(guild);

        StringBuilder result = new StringBuilder(deletedRoles.isEmpty()
                ? "No teamforming roles were found to delete."
                : "Deleted " + deletedRoles.size() + " role(s): " + String.join(", ", deletedRoles) + ".");

        if (panelMessage != null && !panelMessage.isBlank()) {
            boolean messageDeleted = tryDeletePanelMessage(event, panelMessage.trim());
            result.append("\n").append(messageDeleted
                    ? "Panel message deleted."
                    : "Could not delete the panel message — check the link/ID and that the bot can see that channel.");
        }

        event.reply(result.toString()).setEphemeral(true).queue();
    }

    /** Accepts either a full message link or a bare message ID (assumed to be in this channel). */
    private boolean tryDeletePanelMessage(GuildSlashEvent event, String messageLinkOrId) {
        Guild guild = event.getGuild();
        long channelId;
        long messageId;

        Matcher matcher = MESSAGE_LINK_PATTERN.matcher(messageLinkOrId);
        if (matcher.find()) {
            channelId = Long.parseLong(matcher.group(1));
            messageId = Long.parseLong(matcher.group(2));
        } else {
            try {
                channelId = event.getChannel().getIdLong();
                messageId = Long.parseLong(messageLinkOrId);
            } catch (NumberFormatException e) {
                return false;
            }
        }

        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null) return false;

        try {
            channel.deleteMessageById(messageId).complete();
            return true;
        } catch (Exception e) {
            log.warn("Failed to delete teamforming panel message {} in channel {}", messageId, channelId, e);
            return false;
        }
    }
}
