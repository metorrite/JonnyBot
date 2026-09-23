package com.younglings.bot.commands.signup;

import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.awt.Color;

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

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Signups")
                .setColor(Color.CYAN)
                .addField("Builders", "Create a new signup step by step.", false)
                .addField("Commands", "Manage existing signups.", false);

        event.replyEmbeds(embed.build())
                .setEphemeral(true)
                .addComponents(
                        ActionRow.of(
                                Button.primary("signup_builder_type:QUEUE", "Queue"),
                                Button.primary("signup_builder_type:GROUP", "Group"),
                                Button.primary("signup_builder_type:SUBMISSION", "Submission")
                        ),
                        ActionRow.of(
                                Button.secondary("signup_hub_list:_", "List"),
                                Button.secondary("signup_hub_post:_", "Post"),
                                Button.secondary("signup_hub_refresh:_", "Refresh")
                        )
                )
                .queue();
    }
}
