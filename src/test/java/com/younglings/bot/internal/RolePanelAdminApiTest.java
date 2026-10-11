package com.younglings.bot.internal;

import com.younglings.bot.rolepanel.RolePanel;
import com.younglings.bot.rolepanel.RolePanelRepository;
import com.younglings.bot.rolepanel.RolePanelService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RolePanelAdminApiTest {
    private RolePanelRepository repository;
    private RolePanelAdminApi api;
    private Guild guild;
    private Member actor;
    private SelfMember self;

    @BeforeEach
    void setUp() {
        repository = mock(RolePanelRepository.class);
        api = new RolePanelAdminApi(repository, mock(RolePanelService.class));
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(5L);
        self = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(self);
        actor = mock(Member.class);
        when(actor.getId()).thenReturn("7");
        when(repository.list(5L)).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(call -> {
            RolePanel p = call.getArgument(0);
            return new RolePanel(1L, p.guildId(), p.name(), p.title(), p.description(), p.color(), p.mode(), null, null, p.buttons());
        });
    }

    private void role(long id, String name, boolean usable, EnumSet<Permission> permissions) {
        Role role = mock(Role.class);
        when(role.getName()).thenReturn(name);
        when(role.getPermissions()).thenReturn(permissions);
        when(self.canInteract(role)).thenReturn(usable);
        when(guild.getRoleById(id)).thenReturn(role);
    }

    private static DataObject button(String roleId) {
        return DataObject.empty().put("roleId", roleId).put("label", "Red").put("style", "primary");
    }

    private static DataObject body(DataObject... buttons) {
        DataArray array = DataArray.empty();
        for (DataObject b : buttons) array.add(b);
        return DataObject.empty().put("name", "Teams").put("mode", "UNIQUE").put("buttons", array);
    }

    @Test
    void aGoodPanelIsSaved() {
        role(10L, "Red", true, EnumSet.noneOf(Permission.class));

        DataObject saved = api.save(guild, actor, null, body(button("10")));

        assertEquals("Teams", saved.getString("name"));
        verify(repository).save(any());
    }

    @Test
    void aPanelNeedsAName() {
        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, null, DataObject.empty().put("name", "  ")));

        assertEquals(400, error.status);
        verify(repository, never()).save(any());
    }

    @Test
    void aPowerfulOrAboveTheBotRoleIsRefused() {
        role(10L, "Mods", true, EnumSet.of(Permission.BAN_MEMBERS));
        role(11L, "Boss", false, EnumSet.noneOf(Permission.class));

        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, null, body(button("10"), button("11"))));

        assertEquals(2, error.problems.size());
    }

    @Test
    void theSameRoleOnTwoButtonsIsRefused() {
        role(10L, "Red", true, EnumSet.noneOf(Permission.class));

        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, null, body(button("10"), button("10"))));

        assertTrue(error.problems.get(0).contains("already on another button"));
    }

    @Test
    void twoPanelsCannotShareAName() {
        when(repository.list(5L)).thenReturn(List.of(new RolePanel(9L, 5L, "teams", "", "", null, RolePanel.Mode.TOGGLE, null, null, List.of())));

        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, null, body()));

        assertTrue(error.problems.get(0).contains("already called"));
    }

    @Test
    void aPanelThatDoesNotExistIsANotFound() {
        when(repository.get(5L, 3L)).thenReturn(java.util.Optional.empty());

        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.get(guild, "3")).status);
    }
}
