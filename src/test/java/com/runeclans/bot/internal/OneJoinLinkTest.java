package com.runeclans.bot.internal;

import com.runeclans.bot.alliance.AllianceRepository;
import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.clanprofile.ClanRecruiting;
import com.runeclans.bot.clanprofile.ClanRecruitingRepository;
import com.runeclans.bot.clanprofile.JoinLinks;
import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.events.EventTemplates;
import com.runeclans.bot.events.HubModerationRepository;
import com.runeclans.bot.forms.WebFormRepository;
import com.runeclans.bot.runescape.PlayerLinkService;
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
            mock(com.runeclans.bot.events.EventCohostRepository.class), mock(com.runeclans.bot.permission.DashboardAccess.class), mock(MemberEventsApi.class));
        Method host = HubEventsApi.class.getDeclaredMethod("host", JDA.class, long.class);
        host.setAccessible(true);
        String onEvents = ((DataObject) host.invoke(hub, mock(JDA.class), GUILD)).getString("inviteUrl");

        assertEquals("https://discord.gg/server", onClanPage, "Server setup's invite is the server's join link");
        assertEquals(onClanPage, onEvents, "the clan page and the events hub must show the same join link");
        assertEquals(onClanPage, page.getString("joinLink"), "the clan's own site gets the same join link");
    }

    /** T-633: a clan that isn't recruiting keeps its invite off the Clans Hub page, but its own site still shows its join link. */
    @Test
    void aClanThatIsNotRecruitingStillGivesItsSiteTheJoinLink() {
        GuildSettingsService settings = mock(GuildSettingsService.class);
        GuildSettings stored = mock(GuildSettings.class);
        when(stored.inviteUrl()).thenReturn("https://discord.gg/server");
        when(settings.getEffective(GUILD)).thenReturn(stored);
        ClanRecruitingRepository recruiting = mock(ClanRecruitingRepository.class);
        when(recruiting.of(GUILD)).thenReturn(ClanRecruiting.empty(GUILD));
        ClanProfile clan = new ClanProfile(GUILD, ClanProfile.Visibility.PUBLIC, "younglings", "Younglings", null, null, null, null, null, Set.of(),
                ClanProfile.Recruiting.CLOSED, null, null, false, null, null, null, null, null, null);

        DataObject page = DataObject.empty();
        new DirectoryRecruitingSection(recruiting, mock(WebFormRepository.class), new JoinLinks(settings, recruiting)).addToClan(clan, page);
        assertEquals(true, page.getObject("recruitingPost").isNull("inviteUrl"), "the Clans Hub page shows no invite while closed");
        assertEquals("https://discord.gg/server", page.getString("joinLink"));
    }
}
