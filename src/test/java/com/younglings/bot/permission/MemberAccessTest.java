package com.younglings.bot.permission;

import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** "Member level": the Verified Clan Role, or the Support tier and above — never a Guest. */
class MemberAccessTest {
    private static final long GUILD = 1L, MEMBER_ROLE = 50L, GUEST_ROLE = 40L;

    private GuildSettingsService settings;
    private AdminRoleFilter adminRoleFilter;
    private MemberAccess access;
    private Guild guild;
    private Member member;

    @BeforeEach
    void setUp() {
        settings = mock(GuildSettingsService.class);
        adminRoleFilter = mock(AdminRoleFilter.class);
        access = new MemberAccess(settings, adminRoleFilter);
        guild = mock(Guild.class);
        member = mock(Member.class);
        when(guild.getIdLong()).thenReturn(GUILD);
    }

    private void verifiedClanRole(Long id) {
        when(settings.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, null, null, null, null, id, null, null, null, true, null, null, null));
    }

    private static Role role(long id) {
        Role role = mock(Role.class);
        when(role.getIdLong()).thenReturn(id);
        return role;
    }

    @Test
    void aMemberRoleHolderPasses() {
        verifiedClanRole(MEMBER_ROLE);
        Role held = role(MEMBER_ROLE);
        when(member.getRoles()).thenReturn(List.of(held));
        assertTrue(access.isMemberTier(guild, member));
    }

    @Test
    void aGuestDoesNot() {
        verifiedClanRole(MEMBER_ROLE);
        Role held = role(GUEST_ROLE);
        when(member.getRoles()).thenReturn(List.of(held));
        assertFalse(access.isMemberTier(guild, member));
    }

    @Test
    void theSupportTierAndAboveNeedNoMemberRole() {
        verifiedClanRole(MEMBER_ROLE);
        Role held = role(GUEST_ROLE);
        when(member.getRoles()).thenReturn(List.of(held));
        when(adminRoleFilter.isSupportTier(guild, member)).thenReturn(true);
        assertTrue(access.isMemberTier(guild, member));
    }

    @Test
    void withNoMemberRoleConfiguredOnlyStaffGetThrough() {
        verifiedClanRole(null);
        Role held = role(MEMBER_ROLE);
        when(member.getRoles()).thenReturn(List.of(held));
        assertFalse(access.isMemberTier(guild, member));
    }
}
