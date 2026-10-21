package com.younglings.bot.internal;

import com.younglings.bot.permission.PermissionGroup;
import com.younglings.bot.permission.PermissionGroupService;
import com.younglings.bot.roletools.RoleRules.Mode;
import com.younglings.bot.roletools.RoleRules.Status;
import com.younglings.bot.roletools.RoleToolkitRepository;
import com.younglings.bot.roletools.RoleToolkitRepository.Job;
import com.younglings.bot.roletools.RoleToolkitService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleToolkitAdminApiTest {
    private static final long GUILD = 10;

    private RoleToolkitRepository repository;
    private RoleToolkitService service;
    private PermissionGroupService groups;
    private RoleToolkitAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        repository = mock(RoleToolkitRepository.class);
        service = mock(RoleToolkitService.class);
        groups = mock(PermissionGroupService.class);
        api = new RoleToolkitAdminApi(repository, service, groups);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        actor = mock(Member.class);
        when(service.isAdmin(guild, actor)).thenReturn(true);
    }

    private static String[] path(String... parts) {
        String[] all = new String[parts.length + 1];
        all[0] = "role-toolkit";
        System.arraycopy(parts, 0, all, 1, parts.length);
        return all;
    }

    @Test
    void aDeveloperOrAnyoneWhoIsNotAnAdminIsRefusedOnEveryRoute() {
        when(service.isAdmin(guild, actor)).thenReturn(false);

        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "GET", path(), DataObject::empty)).status);
        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "PUT", path("allow", "mods"), DataObject::empty)).status);
        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", path("bulk"), DataObject::empty)).status);
        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", path("bulk", "7", "start"), DataObject::empty)).status);
        verify(repository, never()).setAllowed(anyLong(), anyString(), anySet());
    }

    @Test
    void theAdminGroupCannotBeGivenAList() {
        when(groups.group(GUILD, PermissionGroup.ADMIN)).thenReturn(Optional.of(new PermissionGroup(1, GUILD, "admin", "Admin", true, true, java.util.List.of())));

        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "PUT", path("allow", "admin"), DataObject::empty)).status);
    }

    @Test
    void aListWithARefusedRoleIsNotSavedAtAll() {
        when(groups.group(GUILD, "mods")).thenReturn(Optional.of(new PermissionGroup(2, GUILD, "mods", "Mods", false, false, java.util.List.of())));
        Role good = mock(Role.class);
        Role bad = mock(Role.class);
        when(guild.getRoleById(5L)).thenReturn(good);
        when(guild.getRoleById(6L)).thenReturn(bad);
        when(service.roleRefusal(guild, good)).thenReturn(null);
        when(service.roleRefusal(guild, bad)).thenReturn("kept by the clan role sync");
        DataObject body = DataObject.empty().put("roleIds", DataArray.empty().add("5").add("6"));

        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "PUT", path("allow", "mods"), () -> body)).status);
        verify(repository, never()).setAllowed(anyLong(), anyString(), anySet());
    }

    @Test
    void theDryRunRouteOnlyPlansAndStartingIsAnotherExplicitCall() throws Exception {
        Role source = mock(Role.class);
        Role target = mock(Role.class);
        when(source.getIdLong()).thenReturn(1L);
        when(target.getIdLong()).thenReturn(2L);
        when(guild.getRoleById(1L)).thenReturn(source);
        when(guild.getRoleById(2L)).thenReturn(target);
        Job planned = new Job(7, GUILD, Mode.ADD, 1, 2, 3, Status.PLANNED, 4, 0, 0, Instant.EPOCH);
        when(service.planBulk(guild, actor, Mode.ADD, source, target)).thenReturn(planned);
        when(repository.members(7, 25)).thenReturn(java.util.List.of());

        DataObject answer = api.route(guild, actor, "POST", path("bulk"), () -> DataObject.empty().put("mode", "ADD").put("sourceRoleId", "1").put("targetRoleId", "2"));

        assertEquals("PLANNED", answer.getString("status"));
        verify(service, never()).start(any(), any());
    }

    @Test
    void startingAJobAnswersWithTheReasonItCannot() {
        Job planned = new Job(7, GUILD, Mode.ADD, 1, 2, 3, Status.PLANNED, 4, 0, 0, Instant.EPOCH);
        when(repository.get(GUILD, 7)).thenReturn(Optional.of(planned));
        when(service.start(guild, planned)).thenReturn("Another bulk job is running in this server.");

        assertEquals(409, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", path("bulk", "7", "start"), DataObject::empty)).status);
    }

    @Test
    void aJobOfAnotherServerIsNotFound() {
        when(repository.get(GUILD, 9)).thenReturn(Optional.empty());

        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", path("bulk", "9", "start"), DataObject::empty)).status);
    }
}
