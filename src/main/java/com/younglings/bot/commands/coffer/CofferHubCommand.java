package com.younglings.bot.commands.coffer;

import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

import java.util.List;

/**
 * Single entry point for the coffer system — replaces the five separate coffersubmit/cofferdisplay/
 * cofferdisplaylog/coffertransfer/coffergiveaway commands (retired, not deleted — see
 * {@link CofferCommand}) with one command that opens a personal menu of buttons, each leading to a
 * modal ({@link CofferInteractionListener}) instead of a slash command's own typed parameters.
 */
@Command
public class CofferHubCommand {

    @JDASlashCommand(name = "coffer", description = "Manage the clan coffer")
    public void onCoffer(GuildSlashEvent event) {
        if (event.getGuild() == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "This command can only be used in a server.");
            return;
        }

        Container hub = Container.of(
                TextDisplay.of("# Clan Coffer"),
                TextDisplay.of("Choose an action:"),
                ActionRow.of(
                        Button.primary("coffer_hub_submit", "Submit Donation"),
                        Button.secondary("coffer_hub_display", "Display"),
                        Button.secondary("coffer_hub_log", "Log")
                ),
                ActionRow.of(
                        Button.danger("coffer_hub_transfer", "Transfer"),
                        Button.danger("coffer_hub_giveaway", "Giveaway")
                )
        ).withAccentColor(Containers.PRIMARY);

        event.replyComponents(List.of(hub)).useComponentsV2(true).setEphemeral(true).queue();
    }
}
