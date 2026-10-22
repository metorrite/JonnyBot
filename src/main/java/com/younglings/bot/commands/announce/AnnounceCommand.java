package com.younglings.bot.commands.announce;

import com.younglings.bot.ban.AnnounceService;
import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.jspecify.annotations.Nullable;

/**
 * {@code /announce}: staff post a message in a channel as JonnyBot, optionally pinging one role, {@code @everyone} or {@code @here}.
 * Admins only by default (a server can change that on the dashboard's Commands page). {@code @everyone} and {@code @here} are refused
 * unless the sender may mention everyone in that channel, and a role must be mentionable or the sender must be able to mention
 * everyone; the rules are in {@link com.younglings.bot.ban.AnnouncePolicy}. The dashboard's Post a message page does the same.
 */
@Command
public class AnnounceCommand {
    private final AnnounceService service;

    public AnnounceCommand(AnnounceService service) {
        this.service = service;
    }

    @JDASlashCommand(name = "announce", description = "Post an announcement in a channel, optionally pinging a role, everyone or here")
    public void onAnnounce(GuildSlashEvent event,
                           @SlashOption(description = "Where to post it") GuildChannel channel,
                           @SlashOption(description = "What to say (write \\n for a new line)") String message,
                           @SlashOption(description = "A role to ping") @Nullable Role role,
                           @SlashOption(description = "Ping @everyone (needs the Mention Everyone permission)") @Nullable Boolean everyone,
                           @SlashOption(name = "here", description = "Ping @here (needs the Mention Everyone permission)") @Nullable Boolean here) {
        Member sender = event.getMember();
        if (sender == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "This only works in a server.");
            return;
        }
        try {
            String channelName = service.send(event.getGuild(), sender, channel.getIdLong(), message,
                    Boolean.TRUE.equals(everyone), Boolean.TRUE.equals(here), role == null ? null : role.getIdLong());
            Containers.replyEphemeral(event, Containers.SUCCESS, "Posted in #" + channelName + ".");
        } catch (IllegalStateException e) {
            Containers.replyEphemeral(event, Containers.WARNING, e.getMessage());
        } catch (RuntimeException e) {
            Containers.replyEphemeral(event, Containers.WARNING, "Discord wouldn't let the announcement be posted there.");
        }
    }
}
