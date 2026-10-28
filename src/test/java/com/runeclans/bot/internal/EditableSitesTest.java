package com.runeclans.bot.internal;

import com.runeclans.bot.access.AccessGrantService;
import com.runeclans.bot.clansite.BotlessSiteRepository;
import com.runeclans.bot.clansite.SiteRoleGuard;
import com.runeclans.bot.clansite.SiteRoles;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.runescape.ClanSyncScheduler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** "Sites I can edit": the servers whose clan website the person may edit through the site's own roles, using the same check every site write uses. */
class EditableSitesTest {
    private static final long USER = Long.parseLong("7".repeat(18));

    private Guild guild(long id, Member member) {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(id);
        when(guild.getMemberById(USER)).thenReturn(member);
        return guild;
    }

    @Test
    void listsOnlyTheServersWhereTheSiteRolesAllowEditing() {
        SiteRoleGuard roles = mock(SiteRoleGuard.class);
        Member editor = mock(Member.class);
        Member plain = mock(Member.class);
        Guild editable = guild(1, editor);
        Guild viewOnly = guild(2, plain);
        when(roles.allows(any(Guild.class), eq(editor), eq(SiteRoles.Action.EDIT))).thenReturn(true);
        when(roles.allows(any(Guild.class), eq(plain), eq(SiteRoles.Action.EDIT))).thenReturn(false);
        BotlessAdminApi api = new BotlessAdminApi(null, null, null, null, null, null, roles, mock(BotlessSiteRepository.class),
                mock(GuildSettingsService.class), mock(AccessGrantService.class), mock(BotOwners.class), mock(ClanSyncScheduler.class));
        assertEquals(List.of(editable), api.editableGuilds(List.of(editable, viewOnly), USER));
    }
}
