package com.younglings.bot.commands.runescape;

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
 * Single entry point for linking a RuneScape 3 name to a Discord account and viewing tracked
 * stats. Verification is a human-in-the-loop process, not fully automated: a random Makeover Mage
 * appearance is assigned, the player applies it in-game, and an admin compares the result against
 * their fetched avatar image before the link is confirmed (see {@link RsnInteractionListener}) —
 * pixel-matching a 100x100 avatar image reliably without a way to calibrate against real in-game
 * screenshots wasn't something I wanted to fake, so the actual match/no-match call stays a human
 * judgment call, just with the busywork of fetching the right image automated away.
 */
@Command
public class RsnCommand {

    @JDASlashCommand(name = "rsn", description = "Link your RuneScape 3 name to your Discord account, or view tracked stats")
    public void onRsn(GuildSlashEvent event) {
        if (event.getGuild() == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "This command can only be used in a server.");
            return;
        }

        Container hub = Container.of(
                TextDisplay.of("# RuneScape Account Linking"),
                ActionRow.of(
                        Button.primary("rsn_link:_", "Link My RSN"),
                        Button.secondary("rsn_stats:_", "My Stats"),
                        Button.secondary("rsn_leaderboard:_", "Leaderboard")
                ),
                ActionRow.of(
                        Button.secondary("rsn_review_pending:_", "Review Pending (Admin)")
                )
        ).withAccentColor(Containers.PRIMARY);

        event.replyComponents(List.of(hub)).useComponentsV2(true).setEphemeral(true).queue();
    }
}
