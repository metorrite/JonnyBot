package com.runeclans.bot.commands.configure;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

import java.util.List;

/**
 * Entry point for per-guild bot configuration — gated by Discord's own Administrator permission,
 * not {@code AdminRoleFilter}'s custom Admin role. That role is itself one of the things configured
 * here (see {@link ConfigureInteractionListener}'s Clan section), so bootstrapping a brand-new guild
 * can't depend on it already existing — Administrator is the one permission every guild already has
 * someone holding, with nothing to set up first.
 */
@Command
public class ConfigureCommand {
    private final ConfigureInteractionListener interactionListener;

    public ConfigureCommand(ConfigureInteractionListener interactionListener) {
        this.interactionListener = interactionListener;
    }

    @JDASlashCommand(name = "configure", description = "Configure this server's bot settings (Administrator only)")
    public void onConfigure(GuildSlashEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();

        if (guild == null || member == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "configure.this_command_can_only_be_used"));
            return;
        }

        if (!member.hasPermission(Permission.ADMINISTRATOR)) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "configure.you_need_the_administrator_permission_to"));
            return;
        }

        Container panel = interactionListener.buildMainPanel(guild);
        event.replyComponents(List.of(panel)).useComponentsV2(true).setEphemeral(true).queue();
    }
}
