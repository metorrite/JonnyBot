package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfileService;
import com.younglings.bot.clansite.ClanSiteLayoutRepository;
import com.younglings.bot.clansite.ClanSiteRepository;
import com.younglings.bot.clansite.ClanSiteService;
import com.younglings.bot.clansite.SiteRoleGuard;
import com.younglings.bot.clansite.SiteRoles.Action;
import com.younglings.bot.internal.CommunitySettings.SiteMemberOptions;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.notice.BotOwners;
import com.younglings.bot.premium.Premium;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The clan website routes check the asker's site role before anything else (T-368), and let editors in to the editor's routes only. */
class ClanSiteAdminApiRolesTest {
    private SiteRoleGuard guard;
    private ClanSiteLayoutRepository layouts;
    private ClanSiteApiUnderTest api;
    private Guild guild;
    private Member actor;

    /** The real API with every collaborator mocked. */
    private static final class ClanSiteApiUnderTest {
        final ClanSiteAdminApi api;
        final CommunitySettings community = mock(CommunitySettings.class);

        ClanSiteApiUnderTest(ClanSiteLayoutRepository layouts, SiteRoleGuard guard) {
            api = new ClanSiteAdminApi(mock(ClanSiteRepository.class), layouts, mock(ClanSiteService.class), mock(ClanProfileService.class),
                    mock(BotOwners.class), mock(Premium.class), guard, mock(SiteDiscordService.class), mock(SiteAddressRoutes.class),
                    new SiteMemberOptionsAdminApi(community), mock(SiteMembersAdmin.class));
        }
    }

    @BeforeEach
    void setUp() {
        guard = mock(SiteRoleGuard.class);
        layouts = mock(ClanSiteLayoutRepository.class);
        api = new ClanSiteApiUnderTest(layouts, guard);
        guild = mock(Guild.class);
        actor = mock(Member.class);
    }

    private void editorWithoutPublish() {
        when(guard.allows(eq(guild), eq(actor), any())).thenReturn(false);
        when(guard.allows(guild, actor, Action.EDIT)).thenReturn(true);
    }

    private static String[] path(String p) {
        return p.split("/");
    }

    @Test
    void editorsAreLetInToTheEditorsRoutesOnly() {
        editorWithoutPublish();
        assertTrue(api.api.admits(guild, actor, "GET", path("clan-site")));
        assertTrue(api.api.admits(guild, actor, "GET", path("clan-site/layout")));
        assertTrue(api.api.admits(guild, actor, "PUT", path("clan-site/layout")));
        assertTrue(api.api.admits(guild, actor, "GET", path("clan-site/layout/versions")));
        assertTrue(api.api.admits(guild, actor, "POST", path("clan-site/layout/publish")), "let in, then refused by the publish check");

        assertFalse(api.api.admits(guild, actor, "PUT", path("clan-site")), "the site's settings");
        assertFalse(api.api.admits(guild, actor, "GET", path("clan-site/roles")));
        assertFalse(api.api.admits(guild, actor, "POST", path("clan-site/publish")), "v1's publish stays with dashboard admins");
        assertFalse(api.api.admits(guild, actor, "POST", path("clan-site/delete")));
        assertFalse(api.api.admits(guild, actor, "GET", path("clan-site/export")));
        assertFalse(api.api.admits(guild, actor, "GET", path("polls")), "nothing outside the clan website opens up");
        assertFalse(api.api.admits(guild, actor, "GET", path("clan-site/takedown")));
    }

    @Test
    void someoneWhoIsNotAnEditorIsNotLetIn() {
        when(guard.allows(eq(guild), eq(actor), any())).thenReturn(false);
        assertFalse(api.api.admits(guild, actor, "PUT", path("clan-site/layout")));
    }

    @Test
    void anEditorCantPublishOrRestoreWithoutTheSwitch() {
        editorWithoutPublish();
        ApiError publish = assertThrows(ApiError.class, () -> api.api.route(guild, actor, "POST", path("clan-site/layout/publish"), DataObject::empty));
        assertEquals(403, publish.status);
        ApiError restore = assertThrows(ApiError.class, () -> api.api.route(guild, actor, "POST", path("clan-site/layout/versions/5/restore"), DataObject::empty));
        assertEquals(403, restore.status);
        verifyNoInteractions(layouts);
    }

    @Test
    void onlyTheOwnerDeletesTheSite() {
        when(guard.allows(eq(guild), eq(actor), any())).thenReturn(true);
        when(guard.allows(guild, actor, Action.OWN)).thenReturn(false);
        ApiError delete = assertThrows(ApiError.class, () -> api.api.route(guild, actor, "POST", path("clan-site/delete"), () -> DataObject.empty().put("confirm", "x")));
        assertEquals(403, delete.status, "a dashboard admin who isn't the owner");
        ApiError handOver = assertThrows(ApiError.class, () -> api.api.route(guild, actor, "POST", path("clan-site/roles/hand-over"), DataObject::empty));
        assertEquals(403, handOver.status);
    }

    @Test
    void everyoneCantBeMadeASiteAdminRole() {
        // @everyone's id is the server's: as a site admin role it would let the whole server into the site admin (T-626)
        long server = Long.parseLong("7".repeat(18));
        when(guard.allows(eq(guild), eq(actor), any())).thenReturn(true);
        when(guild.getIdLong()).thenReturn(server);
        when(guild.getRoleById(server)).thenReturn(mock(net.dv8tion.jda.api.entities.Role.class));
        when(guard.settings(server)).thenReturn(com.younglings.bot.clansite.SiteRoles.Settings.DEFAULT);

        ApiError refused = assertThrows(ApiError.class, () -> api.api.route(guild, actor, "PUT", path("clan-site/roles/admin-access"),
                () -> DataObject.empty().put("roleIds", net.dv8tion.jda.api.utils.data.DataArray.empty().add(Long.toString(server)))));
        assertEquals(400, refused.status);
        assertTrue(refused.problems.getFirst().contains("@everyone"), String.valueOf(refused.problems));
        verify(guard, never()).saveAdminAccess(anyLong(), any(), anyLong());
    }

    @Test
    void siteAdminsReadAndSaveTheMemberOptions() {
        when(guard.allows(eq(guild), eq(actor), any())).thenReturn(true);
        when(guild.getIdLong()).thenReturn(7L);
        SelfMember self = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(Permission.NICKNAME_MANAGE)).thenReturn(true);
        when(api.community.siteMemberOptions(7L)).thenReturn(new SiteMemberOptions(false, false));

        DataObject read = api.api.route(guild, actor, "GET", path("clan-site/member-options"), DataObject::empty);
        assertFalse(read.getBoolean("nickname"));
        assertTrue(read.getBoolean("canSetNicknames"));
        assertFalse(read.getBoolean("canManageRoles"));

        DataObject saved = api.api.route(guild, actor, "PUT", path("clan-site/member-options"), () -> DataObject.empty().put("color", true));
        assertTrue(saved.getBoolean("color"));
        assertFalse(saved.getBoolean("nickname"), "a key left out keeps its value");
        verify(api.community).setSiteMemberOptions(7L, new SiteMemberOptions(false, true));
    }

    @Test
    void editorsCantChangeTheMemberOptions() {
        editorWithoutPublish();
        assertFalse(api.api.admits(guild, actor, "PUT", path("clan-site/member-options")));
        ApiError put = assertThrows(ApiError.class, () -> api.api.route(guild, actor, "PUT", path("clan-site/member-options"), () -> DataObject.empty().put("nickname", true)));
        assertEquals(403, put.status);
        verify(api.community, never()).setSiteMemberOptions(anyLong(), any());
    }
}
