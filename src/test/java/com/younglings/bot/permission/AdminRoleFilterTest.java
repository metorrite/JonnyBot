package com.younglings.bot.permission;

import com.younglings.bot.config.BotConfig;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminRoleFilterTest {
    private static final long ADMIN_ROLE_ID = 100L;

    @Mock
    private BotConfig botConfig;
    @Mock
    private Guild guild;
    @Mock
    private Member member;
    @Mock
    private Role adminRole;

    private AdminRoleFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AdminRoleFilter(botConfig);
        lenient().when(adminRole.getPosition()).thenReturn(10);
        lenient().when(guild.getRoleById(ADMIN_ROLE_ID)).thenReturn(adminRole);
    }

    @Test
    void deniesWhenNoAdminRoleConfigured() {
        when(botConfig.getAdminRoleId()).thenReturn(null);

        assertFalse(filter.isAuthorized(guild, member));
    }

    @Test
    void deniesWhenConfiguredAdminRoleNoLongerExistsInGuild() {
        when(botConfig.getAdminRoleId()).thenReturn(ADMIN_ROLE_ID);
        when(guild.getRoleById(ADMIN_ROLE_ID)).thenReturn(null);

        assertFalse(filter.isAuthorized(guild, member));
    }

    @Test
    void deniesMemberWithNoRoles() {
        when(botConfig.getAdminRoleId()).thenReturn(ADMIN_ROLE_ID);
        when(member.getRoles()).thenReturn(List.of());

        assertFalse(filter.isAuthorized(guild, member));
    }

    @Test
    void deniesMemberRankedBelowAdminRole() {
        Role memberRole = mockRoleAtPosition(5);
        when(botConfig.getAdminRoleId()).thenReturn(ADMIN_ROLE_ID);
        when(member.getRoles()).thenReturn(List.of(memberRole));

        assertFalse(filter.isAuthorized(guild, member));
    }

    @Test
    void authorizesMemberWithExactlyTheAdminRole() {
        when(botConfig.getAdminRoleId()).thenReturn(ADMIN_ROLE_ID);
        when(member.getRoles()).thenReturn(List.of(adminRole));

        assertTrue(filter.isAuthorized(guild, member));
    }

    @Test
    void authorizesMemberRankedAboveAdminRole() {
        Role ownerRole = mockRoleAtPosition(20);
        when(botConfig.getAdminRoleId()).thenReturn(ADMIN_ROLE_ID);
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
