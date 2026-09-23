package com.younglings.bot.commands.signup;

import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

/**
 * Retired in favor of {@link SignupHubCommand}, whose single embed puts the Queue/Group/Submission
 * buttons directly on the main {@code /signup} menu instead of behind a separate command and an
 * intermediate type-picker step — kept (not deleted) as reference/fallback, but no longer
 * registered. BotCommands validates that every {@code @JDASlashCommand} method's declaring class
 * is {@code @Command} (and throws at startup otherwise), so all the framework annotations are
 * stripped here, not just the class-level one — this is now plain, uncalled Java, not a disabled
 * command.
 */
public class SignupBuilderCommand {

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
