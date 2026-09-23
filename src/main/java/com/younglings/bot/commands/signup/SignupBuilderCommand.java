package com.younglings.bot.commands.signup;

import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

@Command
public class SignupBuilderCommand {

    @JDASlashCommand(name = "signupbuilder",
            description = "Build a signup step by step instead of filling out one big command")
    public void onSignupBuilder(GuildSlashEvent event) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        event.reply("**What kind of signup do you want to create?**\n"
                        + "Admin controls will be posted in this channel — use `/signup post` afterward "
                        + "to put the public panel wherever you'd like.\n\n"
                        + "**Queue** — ordered, call next/skip one at a time\n"
                        + "**Group** — everyone joins a shared role\n"
                        + "**Submission** — collect free-text entries (up to 3 fields)")
                .setEphemeral(true)
                .addComponents(ActionRow.of(
                        Button.primary("signup_builder_type:QUEUE", "Queue"),
                        Button.primary("signup_builder_type:GROUP", "Group"),
                        Button.primary("signup_builder_type:SUBMISSION", "Submission")
                ))
                .queue();
    }
}
