package com.younglings.bot.commands.embed;

import com.younglings.bot.discord.Containers;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

/**
 * The menu for the bot's pre-designed embeds (see {@link EmbedType}) — post one to a channel, remove one,
 * or remove them all. Reached from {@code /configure}'s Embedded Posts panel now, not its own
 * {@code /embed} command. The buttons are the same {@code embed_*} ids {@link EmbedInteractionListener}
 * has always handled.
 */
public final class EmbedHubPanel {
    private EmbedHubPanel() {}

    /** @param backId custom id of the Back button, which returns to wherever the menu was opened from */
    public static Container build(String backId) {
        return Containers.card(Containers.PRIMARY,
                TextDisplay.of("### Pre-made Embeds\n-# Designed in the bot's code (like the Teamforming panel) rather than written by you. " +
                        "Post one to a channel, or remove one that's already posted."),
                ActionRow.of(
                        Button.success("embed_post:_", "Post"),
                        Button.secondary("embed_remove:_", "Remove"),
                        Button.danger("embed_remove_all:_", "Remove All")),
                ActionRow.of(Button.primary(backId, "Back")),
                Containers.autoCloseNote());
    }
}
