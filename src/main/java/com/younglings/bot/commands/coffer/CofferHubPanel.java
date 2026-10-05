package com.younglings.bot.commands.coffer;

import com.younglings.bot.discord.Containers;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

/**
 * The coffer's menu — Submit Donation, Display, Log, Transfer, Giveaway — now reached from the
 * {@code /rsadmin} panel's Clan Management section rather than its own {@code /coffer} command. The
 * buttons are the same {@code coffer_hub_*} ids {@link CofferInteractionListener} has always handled,
 * so each still opens its own modal or reply; this only builds the menu they sit on.
 */
public final class CofferHubPanel {
    private CofferHubPanel() {}

    /** @param backId custom id of the Back button, which returns to wherever the menu was opened from */
    public static Container build(String backId) {
        return Containers.card(Containers.PRIMARY,
                TextDisplay.of("# Clan Coffer\nChoose an action:"),
                ActionRow.of(
                        Button.primary("coffer_hub_submit", "Submit Donation"),
                        Button.secondary("coffer_hub_display", "Display"),
                        Button.secondary("coffer_hub_log", "Log")),
                ActionRow.of(
                        Button.danger("coffer_hub_transfer", "Transfer"),
                        Button.danger("coffer_hub_giveaway", "Giveaway")),
                ActionRow.of(Button.primary(backId, "Back")),
                Containers.autoCloseNote());
    }
}
