package com.younglings.bot.clansite;

import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.runescape.PlayerLinkService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The viewer answer is worked out from Discord and the site's settings, cached for a minute, and never caches a Discord outage. */
class SiteRoleGuardTest {
    private static final long BASE = Long.parseLong("9".repeat(17));
    private static final long GUILD = BASE + 41;
    private static final long USER = BASE + 42;
    private static final long EDITOR_ROLE = BASE + 43;

    private SiteRoleRepository repository;
    private DashboardAccess access;
    private SiteRoleGuard guard;
    private Guild guild;

    @BeforeEach
    void setUp() {
        repository = mock(SiteRoleRepository.class);
        access = mock(DashboardAccess.class);
        GuildSettingsService settings = mock(GuildSettingsService.class);
        GuildSettings effective = mock(GuildSettings.class);
        when(settings.getEffective(anyLong())).thenReturn(effective);
        PlayerLinkService links = mock(PlayerLinkService.class);
        when(links.getLinksForUser(anyLong(), anyLong())).thenReturn(List.of());
        guard = new SiteRoleGuard(repository, access, settings, links);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getId()).thenReturn(String.valueOf(GUILD));
        when(repository.get(GUILD)).thenReturn(new SiteRoles.Settings(List.of(EDITOR_ROLE), List.of(), List.of(), false, null));
    }

    private Member editor() {
        Member member = mock(Member.class);
        Role role = mock(Role.class);
        when(role.getIdLong()).thenReturn(EDITOR_ROLE);
        when(member.getRoles()).thenReturn(List.of(role));
        when(member.getIdLong()).thenReturn(USER);
        when(access.tierOf(guild, member)).thenReturn(DashboardAccess.Tier.NONE);
        return member;
    }

    @SuppressWarnings("unchecked")
    private void notInServer(ErrorResponse why) {
        CacheRestAction<Member> action = mock(CacheRestAction.class);
        ErrorResponseException error = mock(ErrorResponseException.class);
        when(error.getErrorResponse()).thenReturn(why);
        when(action.complete()).thenThrow(error);
        when(guild.retrieveMemberById(USER)).thenReturn(action);
    }

    @Test
    void anEditorIsAnEditorAndTheAnswerIsCached() {
        Member member = editor();
        when(guild.getMemberById(USER)).thenReturn(member);
        SiteRoleGuard.Viewer viewer = guard.viewer(guild, USER);
        assertEquals(SiteRoles.Role.EDITOR, viewer.role());
        assertTrue(viewer.canEdit());
        assertFalse(viewer.canPublish());
        assertTrue(viewer.staff());

        guard.viewer(guild, USER);
        verify(repository, times(1)).get(GUILD);
        guard.forget(GUILD);
        guard.viewer(guild, USER);
        verify(repository, times(2)).get(GUILD);
    }

    @Test
    void someoneNotInTheServerIsAGuest() {
        when(guild.getMemberById(USER)).thenReturn(null);
        notInServer(ErrorResponse.UNKNOWN_MEMBER);
        SiteRoleGuard.Viewer viewer = guard.viewer(guild, USER);
        assertEquals(SiteRoles.Role.GUEST, viewer.role());
        assertFalse(viewer.inGuild());
        assertTrue(viewer.linkedRsns().isEmpty());
    }

    @Test
    void aDiscordOutageIsNeverCachedAsAGuest() {
        when(guild.getMemberById(USER)).thenReturn(null);
        notInServer(ErrorResponse.UNKNOWN_CHANNEL);
        assertThrows(ErrorResponseException.class, () -> guard.viewer(guild, USER));
        Member member = editor();
        when(guild.getMemberById(USER)).thenReturn(member);
        assertEquals(SiteRoles.Role.EDITOR, guard.viewer(guild, USER).role(), "the next ask looks again");
    }

    @Test
    void writeChecksAskTheSettingsEveryTime() {
        Member member = editor();
        assertTrue(guard.allows(guild, member, SiteRoles.Action.EDIT));
        assertFalse(guard.allows(guild, member, SiteRoles.Action.PUBLISH));
        assertFalse(guard.allows(guild, null, SiteRoles.Action.EDIT));
        when(repository.get(GUILD)).thenReturn(new SiteRoles.Settings(List.of(EDITOR_ROLE), List.of(), List.of(), true, null));
        assertTrue(guard.allows(guild, member, SiteRoles.Action.PUBLISH), "the switch applies at once, no cache on writes");
        verify(access, times(3)).tierOf(any(), any());
    }
}
