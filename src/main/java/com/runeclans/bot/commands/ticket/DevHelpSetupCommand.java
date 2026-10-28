package com.runeclans.bot.commands.ticket;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

import java.util.List;

/**
 * Dev-only: sets up the PvM Help system in this channel for trying out. See {@link DevHelpSetup} for what that does. {@code @Test}-scoped like the
 * other {@code /dev} commands, with {@link BotConfig#getLiveEnvironment()} checked again at runtime.
 */
@Command
public class DevHelpSetupCommand {
    private final BotConfig botConfig;
    private final AdminRoleFilter adminRoleFilter;
    private final DevHelpSetup setup;

    public DevHelpSetupCommand(BotConfig botConfig, AdminRoleFilter adminRoleFilter, DevHelpSetup setup) {
        this.botConfig = botConfig;
        this.adminRoleFilter = adminRoleFilter;
        this.setup = setup;
    }

    @JDASlashCommand(name = "dev", subcommand = "helpsetup", description = "Sets up PvM Help testing here: helper roles, test panels and the helper signup")
    public void onDevHelpSetup(GuildSlashEvent event) {
        if (botConfig.getLiveEnvironment()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.this_command_is_dev_only"));
            return;
        }
        if (event.getGuild() == null || event.getMember() == null || !adminRoleFilter.isAuthorized(event.getGuild(), event.getMember())) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.you_need_the_admin_role_or"));
            return;
        }
        if (!(event.getGuildChannel() instanceof GuildMessageChannel channel) || !channel.canTalk()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "ticket.i_can_t_post_in_this"));
            return;
        }

        event.deferReply(true).queue();
        setup.run(event.getGuild(), channel).whenComplete((summary, error) -> event.getHook().editOriginalComponents(List.of(Containers.toast(
                error == null ? Containers.SUCCESS : Containers.DANGER,
                error == null ? "✅ Set up " + summary + "." : "Couldn't set it up: " + (error.getCause() != null ? error.getCause().getMessage() : error.getMessage())))).useComponentsV2(true).queue());
    }
}
