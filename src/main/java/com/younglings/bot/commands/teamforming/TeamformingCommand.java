package com.younglings.bot.commands.teamforming;

import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Command
public class TeamformingCommand {
    private static final Logger log = LoggerFactory.getLogger(TeamformingCommand.class);

    // Matches a Discord message link's channel and message ID: .../channels/<guild>/<channel>/<message>
    private static final Pattern MESSAGE_LINK_PATTERN = Pattern.compile("channels/\\d+/(\\d+)/(\\d+)/?$");

    private final TeamformingService teamformingService;

    public TeamformingCommand(TeamformingService teamformingService) {
        this.teamformingService = teamformingService;
    }

    @TopLevelSlashCommandData(description = "Manage the boss-event teamforming role panel")
    @JDASlashCommand(name = "teamforming", subcommand = "post",
            description = "Posts the teamforming role panel in a channel, creating any missing roles")
    public void onPost(
            GuildSlashEvent event,
            @SlashOption(description = "Channel to post the panel in") TextChannel channel
    ) {
        if (!isAdmin(event)) {
            event.reply("You need **Manage Server** permission to use this command.").setEphemeral(true).queue();
            return;
        }

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

    @JDASlashCommand(name = "teamforming", subcommand = "revert",
            description = "[Testing] Deletes every teamforming role, optionally deleting a posted panel message too")
    public void onRevert(
            GuildSlashEvent event,
            @SlashOption(description = "Link (or ID, if in this channel) of the panel message to also delete")
            @Nullable String panelMessage
    ) {
        if (!isAdmin(event)) {
            event.reply("You need **Manage Server** permission to use this command.").setEphemeral(true).queue();
            return;
        }

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

    private boolean isAdmin(GuildSlashEvent event) {
        var member = event.getMember();
        return member != null && member.hasPermission(Permission.MANAGE_SERVER);
    }
}
