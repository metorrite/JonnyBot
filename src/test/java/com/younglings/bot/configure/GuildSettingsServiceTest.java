package com.younglings.bot.configure;

import com.younglings.bot.config.BotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A server only inherits the environment's settings (our clan, our role and channel ids) if it is the bot's home server. */
class GuildSettingsServiceTest {
    private static final long HOME = 1L, OTHER = 2L;

    private GuildSettingsRepository repository;
    private BotConfig botConfig;
    private GuildSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(GuildSettingsRepository.class);
        botConfig = mock(BotConfig.class);
        when(botConfig.getGuildId()).thenReturn(HOME);
        when(botConfig.getClanName()).thenReturn("Younglings");
        when(botConfig.getAdminRoleId()).thenReturn(10L);
        when(botConfig.getRenameAlertChannelId()).thenReturn(20L);
        when(botConfig.getVerificationReviewChannelId()).thenReturn(30L);
        when(botConfig.getVerifiedClanRoleId()).thenReturn(40L);
        when(botConfig.getVerifiedNonClanRoleId()).thenReturn(50L);
        when(botConfig.getUnverifiedRoleId()).thenReturn(60L);
        service = new GuildSettingsService(repository, botConfig);
    }

    @Test
    void theHomeServerStillFallsBackToTheEnvironment() {
        GuildSettings s = service.getEffective(HOME);
        assertEquals("Younglings", s.clanName());
        assertEquals(10L, s.adminRoleId());
        assertEquals(20L, s.renameAlertChannelId());
        assertEquals(30L, s.verificationReviewChannelId());
        assertEquals(40L, s.verifiedClanRoleId());
        assertEquals(50L, s.verifiedNonClanRoleId());
        assertEquals(60L, s.unverifiedRoleId());
        assertTrue(s.clanActive());
    }

    @Test
    void anotherServerWithNothingSavedGetsNothingOfOurs() {
        GuildSettings s = service.getEffective(OTHER);
        assertNull(s.clanName(), "it must not be handed our clan");
        assertNull(s.adminRoleId());
        assertNull(s.renameAlertChannelId());
        assertNull(s.verificationReviewChannelId());
        assertNull(s.verifiedClanRoleId());
        assertNull(s.verifiedNonClanRoleId());
        assertNull(s.unverifiedRoleId());
        assertFalse(s.clanActive(), "no clan, so no clan features");
    }

    @Test
    void anotherServersOwnSavedSettingsStillApply() {
        when(repository.get(OTHER)).thenReturn(new GuildSettings(OTHER, "Other Clan", 7L, 8L, 9L, 11L, 12L, 13L, 14L, true, "Other Clan", null, null));
        GuildSettings s = service.getEffective(OTHER);
        assertEquals("Other Clan", s.clanName());
        assertEquals(7L, s.adminRoleId());
        assertEquals(9L, s.verificationReviewChannelId());
        assertEquals(11L, s.verifiedClanRoleId());
    }

    @Test
    void aSavedSettingBeatsTheEnvironmentOnTheHomeServerToo() {
        when(repository.get(HOME)).thenReturn(new GuildSettings(HOME, "Renamed Clan", null, null, null, null, null, null, null, true, "Renamed Clan", null, null));
        GuildSettings s = service.getEffective(HOME);
        assertEquals("Renamed Clan", s.clanName());
        assertEquals(10L, s.adminRoleId(), "what wasn't saved still comes from the environment");
    }

    @Test
    void withNoHomeServerConfiguredEveryServerKeepsTheOldBehaviour() {
        when(botConfig.getGuildId()).thenReturn(null);
        assertEquals("Younglings", service.getEffective(OTHER).clanName());
        assertEquals(10L, service.getEffective(OTHER).adminRoleId());
    }
}
