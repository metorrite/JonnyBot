package com.younglings.bot.commands.configure;

import com.younglings.bot.commands.ticket.HelpOnboarding;
import com.younglings.bot.ticket.TicketModels.HelpSettings;
import com.younglings.bot.ticket.TicketRepository;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.tree.ComponentTree;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PvmHelpConfigurePanelTest {
    private static Container panel(HelpSettings settings) {
        TicketRepository repository = mock(TicketRepository.class);
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(1L);
        when(repository.getHelpSettings(1L)).thenReturn(settings);
        return new PvmHelpConfigureListener(repository, mock(HelpOnboarding.class)).buildPanel(guild);
    }

    private static String text(Container container) {
        return String.join("\n", ComponentTree.of(List.of(container)).findAll(TextDisplay.class).stream().map(TextDisplay::getContent).toList());
    }

    @Test
    void thePanelFitsInOneMessageAndHasTheFourSettingButtons() {
        Container container = panel(HelpSettings.defaults(1));
        new MessageCreateBuilder().useComponentsV2(true).setComponents(container).build();

        List<String> labels = ComponentTree.of(List.of(container)).findAll(Button.class).stream().map(Button::getLabel).toList();
        assertEquals(List.of("Edit Guidelines", "Member Pings", "Guest Pings", "Earlier Attempts", "Back"), labels);
    }

    @Test
    void itShowsTheDefaultsPlainly() {
        String text = text(panel(HelpSettings.defaults(1)));
        assertTrue(text.contains("escalate after 72h"), text);
        assertTrue(text.contains("no pings"), "guest pings are off");
        assertTrue(text.contains("Master, Grandmaster"));
        assertTrue(text.contains("built-in draft"));
        assertTrue(text.contains("not posted yet"));
    }

    @Test
    void itShowsGuestTimersOnceGuestPingsAreOn() {
        String text = text(panel(new HelpSettings(1, 10L, 11L, "custom", false, null, true, true, 24, false, "Master", 5L, 6L)));
        assertTrue(text.contains("<@&10>") && text.contains("<@&11>"));
        assertTrue(text.contains("no ping when opened, never escalate"), "member timers: " + text);
        assertTrue(text.contains("escalate after 24h"));
        assertTrue(text.contains("no extra requirement"));
        assertTrue(text.contains("edited") && text.contains("<#5>"));
    }

    @Test
    void hoursAreAWholeNumberOrBlank() {
        assertEquals(72, PvmHelpConfigureListener.parseHours(" 72 "));
        assertEquals(null, PvmHelpConfigureListener.parseHours("  "));
        org.junit.jupiter.api.Assertions.assertThrows(NumberFormatException.class, () -> PvmHelpConfigureListener.parseHours("a day"));
    }
}
