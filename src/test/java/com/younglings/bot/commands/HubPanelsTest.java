package com.younglings.bot.commands;

import com.younglings.bot.commands.coffer.CofferHubPanel;
import com.younglings.bot.commands.embed.EmbedHubPanel;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.tree.ComponentTree;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The two menus that used to be slash commands (/coffer, /embed) now live inside panels; each must fit in a message and keep the button ids its listener handles. */
class HubPanelsTest {
    private static List<String> ids(Container panel) {
        return ComponentTree.of(List.of(panel)).findAll(Button.class).stream().map(Button::getCustomId).toList();
    }

    private static void assertFits(Container panel) {
        new MessageCreateBuilder().useComponentsV2(true).setComponents(panel).build();
    }

    @Test
    void theCofferMenuKeepsItsButtonsAndGainsABackButton() {
        Container panel = CofferHubPanel.build("rsnadmin_back:_");
        assertFits(panel);
        assertEquals(List.of("coffer_hub_submit", "coffer_hub_display", "coffer_hub_log", "coffer_hub_transfer", "coffer_hub_giveaway", "rsnadmin_back:_"), ids(panel));
    }

    @Test
    void thePremadeEmbedMenuKeepsItsButtonsAndGainsABackButton() {
        Container panel = EmbedHubPanel.build("configure_announce_main:_");
        assertFits(panel);
        assertEquals(List.of("embed_post:_", "embed_remove:_", "embed_remove_all:_", "configure_announce_main:_"), ids(panel));
    }
}
