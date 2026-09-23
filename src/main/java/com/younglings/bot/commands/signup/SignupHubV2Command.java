package com.younglings.bot.commands.signup;

import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.annotations.Test;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

import java.awt.Color;
import java.util.List;

/**
 * Preview of {@code /signup} rebuilt on Discord's Components V2 (a {@link Container} instead of a
 * classic embed + separate button row) — see {@link SignupHubV2InteractionListener} for the
 * {@code signupv2_}-prefixed handlers. {@code @Test} scope keeps this dev-guild-only and out of
 * production entirely, deliberately kept as a fully separate command/ID namespace from the real
 * {@link SignupHubCommand}/{@link SignupInteractionListener} rather than converting those in
 * place, so the working, already-battle-tested {@code /signup} is completely unaffected either
 * way — promote this to replace it (or discard it) once you've compared the two.
 * <p>
 * Scope note: only the hub itself and its own builder/list/post/refresh flow are converted here.
 * The actual posted signup panels (the Queue/Group/Submission embeds members Join/Leave from, and
 * their admin control panels) are a separate, already-stable subsystem built in
 * {@code SignupService} and were left untouched — say the word if you want those converted too
 * once you've seen how this looks.
 */
@Command
public class SignupHubV2Command {

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @Test({})
    @JDASlashCommand(name = "signupv2", description = "[Preview] Components V2 rebuild of /signup")
    public void onSignupV2(GuildSlashEvent event) {
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
                        Button.primary("signupv2_builder_type:QUEUE", "Queue"),
                        Button.primary("signupv2_builder_type:GROUP", "Group"),
                        Button.primary("signupv2_builder_type:SUBMISSION", "Submission")
                ),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("**Commands**\nManage existing signups."),
                ActionRow.of(
                        Button.secondary("signupv2_hub_list:_", "List"),
                        Button.secondary("signupv2_hub_post:_", "Post"),
                        Button.secondary("signupv2_hub_refresh:_", "Refresh")
                )
        ).withAccentColor(Color.CYAN);

        event.replyComponents(List.of(hub))
                .useComponentsV2(true)
                .setEphemeral(true)
                .queue();
    }
}
