package com.younglings.bot.permission;

import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminRoleFilterTest {
    private static final long ADMIN_ROLE_ID = 100L;

    @Mock
    private GuildSettingsService guildSettingsService;
    @Mock
    private Guild guild;
    @Mock
    private Member member;
    @Mock
    private Role adminRole;

    private AdminRoleFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AdminRoleFilter(guildSettingsService);
        lenient().when(adminRole.getPosition()).thenReturn(10);
        lenient().when(guild.getRoleById(ADMIN_ROLE_ID)).thenReturn(adminRole);
    }

    private void withAdminRoleId(Long adminRoleId) {
        when(guildSettingsService.getEffective(anyLong())).thenReturn(
                new GuildSettings(0L, null, adminRoleId, null, null, null, null, null, null, true, null, null));
    }

    private void withRoles(Long adminRoleId, Long supportRoleId) {
        when(guildSettingsService.getEffective(anyLong())).thenReturn(
                new GuildSettings(0L, null, adminRoleId, null, null, null, null, null, null, true, null, supportRoleId));
    }

    @Test
    void theSupportRoleHolderIsSupportTierButNotAdminTier() {
        Role supportRole = Mockito.mock(Role.class);
        when(supportRole.getIdLong()).thenReturn(200L);
        when(supportRole.getPosition()).thenReturn(5);
        withRoles(ADMIN_ROLE_ID, 200L);
        when(member.getRoles()).thenReturn(List.of(supportRole));

        assertTrue(filter.isSupportTier(guild, member));
        assertFalse(filter.isAuthorized(guild, member));
    }

    @Test
    void anAdminIsAlsoSupportTier() {
        withRoles(ADMIN_ROLE_ID, 200L);
        when(member.getRoles()).thenReturn(List.of(adminRole));

        assertTrue(filter.isSupportTier(guild, member));
    }

    @Test
    void aModeratorIsNotSupportTierUnlessTheyAlsoHoldTheSupportRole() {
        Role moderator = mockRoleAtPosition(5);
        when(moderator.getIdLong()).thenReturn(300L);
        withRoles(ADMIN_ROLE_ID, 200L);
        when(member.getRoles()).thenReturn(List.of(moderator));

        assertFalse(filter.isSupportTier(guild, member));
    }

    @Test
    void noSupportRoleConfiguredMeansNoSupportTierAtAll() {
        Role someRole = mockRoleAtPosition(5);
        withRoles(ADMIN_ROLE_ID, null);
        when(member.getRoles()).thenReturn(List.of(someRole));

        assertFalse(filter.isSupportTier(guild, member));
    }

    @Test
    void deniesWhenNoAdminRoleConfigured() {
        withAdminRoleId(null);

        assertFalse(filter.isAuthorized(guild, member));
    }

    @Test
    void deniesWhenConfiguredAdminRoleNoLongerExistsInGuild() {
        withAdminRoleId(ADMIN_ROLE_ID);
        when(guild.getRoleById(ADMIN_ROLE_ID)).thenReturn(null);

        assertFalse(filter.isAuthorized(guild, member));
    }

    @Test
    void deniesMemberWithNoRoles() {
        withAdminRoleId(ADMIN_ROLE_ID);
        when(member.getRoles()).thenReturn(List.of());

        assertFalse(filter.isAuthorized(guild, member));
    }

    @Test
    void deniesMemberRankedBelowAdminRole() {
        Role memberRole = mockRoleAtPosition(5);
        withAdminRoleId(ADMIN_ROLE_ID);
        when(member.getRoles()).thenReturn(List.of(memberRole));

        assertFalse(filter.isAuthorized(guild, member));
    }

    @Test
    void authorizesMemberWithExactlyTheAdminRole() {
        withAdminRoleId(ADMIN_ROLE_ID);
        when(member.getRoles()).thenReturn(List.of(adminRole));

        assertTrue(filter.isAuthorized(guild, member));
    }

    @Test
    void authorizesMemberRankedAboveAdminRole() {
        Role ownerRole = mockRoleAtPosition(20);
        withAdminRoleId(ADMIN_ROLE_ID);
        // getRoles() is highest-first; the member's top role is what matters.
        when(member.getRoles()).thenReturn(List.of(ownerRole, adminRole));

        assertTrue(filter.isAuthorized(guild, member));
    }

    private Role mockRoleAtPosition(int position) {
        Role role = Mockito.mock(Role.class);
        lenient().when(role.getPosition()).thenReturn(position);
        return role;
    }
}
