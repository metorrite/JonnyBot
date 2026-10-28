package com.runeclans.bot.internal;

import com.runeclans.bot.alliance.AllianceRepository;
import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.clanprofile.JoinLinks;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.events.EventTemplates;
import com.runeclans.bot.events.HubModerationRepository;
import com.runeclans.bot.runescape.PlayerLinkService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The public events hub names a host by its clan profile or server name, never by its Discord id (T-182). */
class HubEventsHostTest {
    private static final long GUILD = 1234567890123456789L;

    private final ClanProfileService profiles = mock(ClanProfileService.class);
    private final JoinLinks joinLinks = mock(JoinLinks.class);
    private final HubEventsApi api = new HubEventsApi(mock(EventRepository.class), mock(EventService.class), mock(EventTemplates.class), joinLinks, profiles,
            mock(AllianceRepository.class), mock(PlayerLinkService.class), mock(HubModerationRepository.class),
            mock(com.runeclans.bot.events.EventCohostRepository.class), mock(com.runeclans.bot.permission.DashboardAccess.class), mock(MemberEventsApi.class));

    HubEventsHostTest() {
        when(joinLinks.of(GUILD, null)).thenReturn("https://discord.gg/abc");
    }

    private static void noDiscordId(DataObject host) {
        assertFalse(host.hasKey("guildId"), host.toString());
        assertFalse(host.toString().contains(Long.toString(GUILD)), "the server's id appears in " + host);
    }

    @Test
    void aServerWithoutAListedProfileIsNamedByItsServerName() {
        when(profiles.publicView(GUILD, false)).thenReturn(Optional.empty());
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        when(guild.getName()).thenReturn("Some Server");
        when(jda.getGuildById(GUILD)).thenReturn(guild);

        DataObject host = api.host(jda, GUILD);
        assertEquals("Some Server", host.getString("name"));
        assertEquals("https://discord.gg/abc", host.getString("inviteUrl"));
        noDiscordId(host);
    }

    @Test
    void aListedClanIsNamedByItsProfile() {
        ClanProfile p = new ClanProfile(GUILD, ClanProfile.Visibility.PUBLIC, "younglings", "Younglings", null, null, null, null, null, Set.of(),
                ClanProfile.Recruiting.OPEN, null, null, false, null, null, null, null, null, null);
        when(profiles.publicView(GUILD, false)).thenReturn(Optional.of(p));

        DataObject host = api.host(mock(JDA.class), GUILD);
        assertEquals("Younglings", host.getString("name"));
        assertEquals("younglings", host.getString("slug"));
        noDiscordId(host);
    }
}
