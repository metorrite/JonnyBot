package com.younglings.bot.commands.signup;

import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

import java.util.List;

/**
 * Single entry point for the signup system — replaces /signup queue|group|submission (builders)
 * and /signup list|post|refresh (utilities), all retired but kept (see {@link SignupCommand},
 * {@link SignupManagementCommand}, {@link SignupBuilderCommand}), with one command and one
 * button-driven menu. Builder buttons reuse the existing signup_builder_type:* handlers in
 * {@link SignupInteractionListener} directly; utility buttons are handled there too.
 */
@Command
public class SignupHubCommand {

    @JDASlashCommand(name = "signup", description = "Create and manage signups")
    public void onSignup(GuildSlashEvent event) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        Container hub = Container.of(
                TextDisplay.of("# Signups"),
                TextDisplay.of("Create and manage signups."),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("**Builders**\nCreate a new signup step by step."),
                ActionRow.of(
                        Button.primary("signup_builder_type:QUEUE", "Queue"),
                        Button.primary("signup_builder_type:GROUP", "Group"),
                        Button.primary("signup_builder_type:SUBMISSION", "Submission")
                ),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("**Commands**\nManage existing signups."),
                ActionRow.of(
                        Button.secondary("signup_hub_list:_", "List"),
                        Button.secondary("signup_hub_post:_", "Post"),
                        Button.secondary("signup_hub_refresh:_", "Refresh")
                )
        ).withAccentColor(Containers.PRIMARY);

        event.replyComponents(List.of(hub))
                .useComponentsV2(true)
                .setEphemeral(true)
                .queue();
    }
}
