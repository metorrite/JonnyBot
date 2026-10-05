package com.younglings.bot.commands.configure;

import com.younglings.bot.announcement.AnnouncementRepository;
import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.runescape.ClanVerificationService;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.tree.ComponentTree;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Builds each panel the way Discord will receive it. JDA enforces the 40-component and 4000-character
 * message limits when a message is built (not when a component is created), so a panel that grows past
 * them only fails at runtime — these builds are what catch that, and pin the Clan Setup button's color.
 */
class ConfigurePanelsTest {
    private static final long GUILD_ID = 1L;

    private GuildSettingsService settingsService;
    private AnnouncementRepository announcements;
    private ConfigureInteractionListener listener;
    private Guild guild;

    @BeforeEach
    void setUp() {
        settingsService = mock(GuildSettingsService.class);
        announcements = mock(AnnouncementRepository.class);
        listener = new ConfigureInteractionListener(settingsService, mock(ClanVerificationService.class), announcements);

        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD_ID);
        // The role dropdowns list up to 24 roles — the worst case for the verification panel's text.
        List<Role> roles = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Role role = mock(Role.class);
            when(role.getName()).thenReturn("Role " + i);
            when(role.getId()).thenReturn(String.valueOf(1000 + i));
            when(role.getIdLong()).thenReturn(1000L + i);
            when(role.isPublicRole()).thenReturn(false);
            roles.add(role);
        }
        when(guild.getRoles()).thenReturn(roles);
        when(announcements.countPostedEmbeds(anyLong())).thenReturn(3);
    }

    private static GuildSettings settings(String clanName, boolean enabled) {
        return new GuildSettings(GUILD_ID, enabled ? clanName : null, 5L, 10L, 11L, 12L, 13L, 14L, 15L, enabled, clanName, 16L);
    }

    /** Throws if Discord's message limits would reject this. */
    private static void assertFitsInAMessage(Container container) {
        new MessageCreateBuilder().useComponentsV2(true).setComponents(container).build();
    }

    private static ButtonStyle clanButtonStyle(Container panel) {
        return ComponentTree.of(List.of(panel)).findAll(Button.class).stream()
                .filter(b -> "configure_clan:_".equals(b.getCustomId()))
                .findFirst().orElseThrow().getStyle();
    }

    @Test
    void clanSetupIsRedUntilAClanIsSet() {
        when(settingsService.getEffective(GUILD_ID)).thenReturn(settings(null, true));
        Container panel = listener.buildMainPanel(guild);
        assertFitsInAMessage(panel);
        assertEquals(ButtonStyle.DANGER, clanButtonStyle(panel));
    }

    @Test
    void clanSetupIsGreenOnceAClanIsSet() {
        when(settingsService.getEffective(GUILD_ID)).thenReturn(settings("Younglings", true));
        Container panel = listener.buildMainPanel(guild);
        assertFitsInAMessage(panel);
        assertEquals(ButtonStyle.SUCCESS, clanButtonStyle(panel));
    }

    @Test
    void clanSetupIsGrayWhileClanFeaturesAreOff() {
        when(settingsService.getEffective(GUILD_ID)).thenReturn(settings("Younglings", false));
        Container panel = listener.buildMainPanel(guild);
        assertFitsInAMessage(panel);
        assertEquals(ButtonStyle.SECONDARY, clanButtonStyle(panel));
    }

    @Test
    void embeddedPostsButtonShowsHowManyAreLive() {
        when(settingsService.getEffective(GUILD_ID)).thenReturn(settings("Younglings", true));
        Container panel = listener.buildMainPanel(guild);
        assertTrue(ComponentTree.of(List.of(panel)).findAll(Button.class).stream().anyMatch(b -> "Embedded Posts (3)".equals(b.getLabel())));
    }

    @Test
    void everySectionHasItsRenamedButton() {
        when(settingsService.getEffective(GUILD_ID)).thenReturn(settings("Younglings", true));
        List<String> labels = ComponentTree.of(List.of(listener.buildMainPanel(guild))).findAll(Button.class).stream().map(Button::getLabel).toList();
        assertEquals(List.of("Clan Setup", "RSN Link", "Embedded Posts (3)", "Tracker Channels", "Command Only Channels"), labels);
    }

    @Test
    void clanSetupPanelFitsInEveryState() {
        for (GuildSettings state : List.of(settings(null, true), settings("Younglings", true), settings("Younglings", false))) {
            when(settingsService.getEffective(GUILD_ID)).thenReturn(state);
            assertFitsInAMessage(listener.buildClanPanel(guild));
        }
    }

    @Test
    void rsnLinkPanelFitsEvenWithEverythingSet() {
        when(settingsService.getEffective(GUILD_ID)).thenReturn(settings("Younglings", true));
        assertFitsInAMessage(listener.buildVerificationPanel(guild));
    }

    @Test
    void rsnLinkPanelAlsoHoldsTheRenameAlertChannel() {
        when(settingsService.getEffective(GUILD_ID)).thenReturn(settings("Younglings", true));
        Container panel = listener.buildVerificationPanel(guild);
        assertTrue(ComponentTree.of(List.of(panel)).findAll(net.dv8tion.jda.api.components.selections.EntitySelectMenu.class).stream()
                .anyMatch(menu -> "configure_rename_channel:_".equals(menu.getCustomId())));
    }

    @Test
    void everyPanelEndsWithTheAutoCloseNote() {
        when(settingsService.getEffective(GUILD_ID)).thenReturn(settings("Younglings", true));
        for (Container panel : List.of(listener.buildMainPanel(guild), listener.buildClanPanel(guild), listener.buildVerificationPanel(guild))) {
            var last = panel.getComponents().getLast();
            assertTrue(last.toString().contains("closes") || last instanceof net.dv8tion.jda.api.components.textdisplay.TextDisplay t && t.getContent().contains("This panel closes"));
        }
    }
}
