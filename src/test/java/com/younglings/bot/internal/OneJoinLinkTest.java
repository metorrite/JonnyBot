package com.younglings.bot.internal;

import com.younglings.bot.alliance.AllianceRepository;
import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileService;
import com.younglings.bot.clanprofile.ClanRecruiting;
import com.younglings.bot.clanprofile.ClanRecruitingRepository;
import com.younglings.bot.clanprofile.JoinLinks;
import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.EventTemplates;
import com.younglings.bot.events.HubModerationRepository;
import com.younglings.bot.forms.WebFormRepository;
import com.younglings.bot.runescape.PlayerLinkService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A server that typed a different invite on Server setup and on its recruiting post shows ONE join link on the hub: the same on its clan page's
 * recruiting post and on its events' "join the Discord first" (T-181).
 */
class OneJoinLinkTest {
    private static final long GUILD = 42L;

    @Test
    void theClanPageAndTheEventsHubShowTheSameJoinLink() throws Exception {
        GuildSettingsService settings = mock(GuildSettingsService.class);
        GuildSettings stored = mock(GuildSettings.class);
        when(stored.inviteUrl()).thenReturn("https://discord.gg/server");
        when(settings.getEffective(GUILD)).thenReturn(stored);
        ClanRecruitingRepository recruiting = mock(ClanRecruitingRepository.class);
        when(recruiting.of(GUILD)).thenReturn(new ClanRecruiting(GUILD, "We raid on Fridays", null, "https://discord.gg/recruits", null));
        ClanProfile clan = new ClanProfile(GUILD, ClanProfile.Visibility.PUBLIC, "younglings", "Younglings", null, null, null, null, null, Set.of(),
                ClanProfile.Recruiting.OPEN, null, "https://discord.gg/apply", false, null, null, null, null, null, null);
        ClanProfileService profiles = mock(ClanProfileService.class);
        when(profiles.publicView(GUILD, false)).thenReturn(Optional.of(clan));
        JoinLinks joinLinks = new JoinLinks(settings, recruiting);

        DataObject page = DataObject.empty();
        new DirectoryRecruitingSection(recruiting, mock(WebFormRepository.class), joinLinks).addToClan(clan, page);
        String onClanPage = page.getObject("recruitingPost").getString("inviteUrl");

        HubEventsApi hub = new HubEventsApi(mock(EventRepository.class), mock(EventService.class), mock(EventTemplates.class), joinLinks, profiles,
                mock(AllianceRepository.class), mock(PlayerLinkService.class), mock(HubModerationRepository.class),
            mock(com.younglings.bot.events.EventCohostRepository.class), mock(com.younglings.bot.permission.DashboardAccess.class), mock(MemberEventsApi.class));
        Method host = HubEventsApi.class.getDeclaredMethod("host", JDA.class, long.class);
        host.setAccessible(true);
        String onEvents = ((DataObject) host.invoke(hub, mock(JDA.class), GUILD)).getString("inviteUrl");

        assertEquals("https://discord.gg/server", onClanPage, "Server setup's invite is the server's join link");
        assertEquals(onClanPage, onEvents, "the clan page and the events hub must show the same join link");
    }
}
