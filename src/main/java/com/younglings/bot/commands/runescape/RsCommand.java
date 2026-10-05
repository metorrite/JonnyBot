package com.younglings.bot.commands.runescape;

import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;

/**
 * Single entry point for RS3 tracking as a member sees it, with linking as a hard gate rather than
 * one option among others: an unlinked member with no verification already in progress gets the
 * link modal immediately — there's no hub screen to wander past it from. Once a verification is
 * underway, this shows a self-service status view instead (not the admin's full queue — see
 * {@link RsAdminInteractionListener} for that); once linked, it shows the member's own profile(s)
 * directly. See {@link RsInteractionListener} for all of the above.
 */
@Command
public class RsCommand {
    private final RsInteractionListener interactionListener;

    public RsCommand(RsInteractionListener interactionListener) {
        this.interactionListener = interactionListener;
    }

    @JDASlashCommand(name = "rs", description = "Link your RuneScape 3 name, check verification status, or manage your linked account(s)")
    public void onRs(GuildSlashEvent event) {
        if (event.getGuild() == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "This command can only be used in a server.");
            return;
        }

        interactionListener.openRs(event, event.getGuild(), event.getUser().getIdLong());
    }
}
