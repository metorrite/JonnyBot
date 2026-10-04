package com.younglings.bot.commands.runescape;

import com.younglings.bot.commands.configure.ConfigureInteractionListener;
import com.younglings.bot.runescape.ClanSyncService;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.tree.ComponentTree;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The admin panel's clan sections only exist when the server has a clan; Bot Management always does. */
class RsAdminPanelTest {
    private ClanSyncService clanSyncService;
    private RsAdminInteractionListener listener;
    private Guild guild;

    @BeforeEach
    void setUp() {
        clanSyncService = mock(ClanSyncService.class);
        listener = new RsAdminInteractionListener(null, null, null, null, null, clanSyncService, null, null, null, null, null,
                mock(ConfigureInteractionListener.class));
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(1L);
    }

    private static List<String> ids(Container panel) {
        return ComponentTree.of(List.of(panel)).findAll(Button.class).stream().map(Button::getCustomId).toList();
    }

    @Test
    void withAClanEverythingShowsAndFitsInAMessage() {
        when(clanSyncService.getClanName(1L)).thenReturn("Younglings");
        Container panel = listener.buildPanel(guild);
        new MessageCreateBuilder().useComponentsV2(true).setComponents(panel).build();

        assertTrue(ids(panel).contains("rsnadmin_syncclan:_"));
        assertTrue(ids(panel).contains("clanpoints_open:_"));
        assertTrue(ids(panel).contains("rsnadmin_open_configure:_"));
    }

    @Test
    void withoutAClanTheClanToolsAreHiddenButConfigureIsStillThere() {
        when(clanSyncService.getClanName(1L)).thenReturn(null);
        Container panel = listener.buildPanel(guild);
        new MessageCreateBuilder().useComponentsV2(true).setComponents(panel).build();

        assertFalse(ids(panel).contains("rsnadmin_syncclan:_"));
        assertFalse(ids(panel).contains("rsnadmin_clanlist:_"));
        assertFalse(ids(panel).contains("clanpoints_open:_"));
        assertTrue(ids(panel).contains("rsnadmin_open_configure:_"));
        assertTrue(ids(panel).contains("rsnadmin_manualverify:_"), "manual verification must stay so a brand-new server can bootstrap");
    }
}
