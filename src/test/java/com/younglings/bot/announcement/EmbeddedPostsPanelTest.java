package com.younglings.bot.announcement;

import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.tree.ComponentTree;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class EmbeddedPostsPanelTest {
    @Test
    void thePanelOffersThePremadeEmbedsFitsInAMessageAndKeepsBackAtTheBottom() {
        AnnouncementInteractionListener listener = new AnnouncementInteractionListener(mock(AnnouncementService.class));
        Container panel = listener.buildMainPanel(1L);
        new MessageCreateBuilder().useComponentsV2(true).setComponents(panel).build();

        List<String> ids = ComponentTree.of(List.of(panel)).findAll(Button.class).stream().map(Button::getCustomId).toList();
        assertTrue(ids.contains("configure_announce_premade:_"));
        assertEquals("configure_back:_", ids.getLast());
        assertEquals(4, ids.stream().filter(id -> id.startsWith("configure_announce_preset:")).count(), "the four written presets are still there");
    }
}
