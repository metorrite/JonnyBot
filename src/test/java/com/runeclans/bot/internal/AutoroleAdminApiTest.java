package com.runeclans.bot.internal;

import com.runeclans.bot.autorole.AutoroleConfig;
import com.runeclans.bot.autorole.AutoroleRepository;
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

class AutoroleAdminApiTest {
    private AutoroleRepository repository;
    private AutoroleAdminApi api;
    private Guild guild;
    private Member actor;
    private SelfMember self;

    @BeforeEach
    void setUp() {
        repository = mock(AutoroleRepository.class);
        api = new AutoroleAdminApi(repository);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(5L);
        self = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(self);
        actor = mock(Member.class);
        when(actor.getId()).thenReturn("7");
        when(repository.get(5L)).thenReturn(AutoroleConfig.defaults(5L));
    }

    private Role role(long id, String name, boolean usable, EnumSet<Permission> permissions) {
        Role role = mock(Role.class);
        when(role.getName()).thenReturn(name);
        when(role.isPublicRole()).thenReturn(false);
        when(role.isManaged()).thenReturn(false);
        when(role.getPermissions()).thenReturn(permissions);
        when(self.canInteract(role)).thenReturn(usable);
        when(guild.getRoleById(id)).thenReturn(role);
        return role;
    }

    private static DataArray ids(String... ids) {
        DataArray array = DataArray.empty();
        for (String id : ids) array.add(id);
        return array;
    }

    @Test
    void anOrdinaryRoleTheBotCanGiveIsSaved() {
        role(10L, "Member", true, EnumSet.noneOf(Permission.class));

        api.save(guild, actor, DataObject.empty().put("enabled", true).put("delaySeconds", 30).put("memberRoleIds", ids("10")));

        verify(repository).save(new AutoroleConfig(5L, true, 30, List.of(10L), List.of()));
    }

    @Test
    void aRoleAboveTheBotIsRefusedAndNothingIsSaved() {
        role(10L, "Member", false, EnumSet.noneOf(Permission.class));

        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, DataObject.empty().put("memberRoleIds", ids("10"))));

        assertEquals(400, error.status);
        assertTrue(error.problems.get(0).contains("@Member"));
        verify(repository, never()).save(any());
    }

    @Test
    void aPowerfulRoleIsRefused() {
        role(10L, "Mods", true, EnumSet.of(Permission.BAN_MEMBERS));

        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, DataObject.empty().put("botRoleIds", ids("10")))).status);
    }

    @Test
    void aDeletedRoleIsRefused() {
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, DataObject.empty().put("memberRoleIds", ids("99")))).status);
    }

    @Test
    void theDelayHasALimit() {
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, DataObject.empty().put("delaySeconds", 9_999_999))).status);
    }

    @Test
    void aSaveThatLeavesAFieldOutKeepsWhatTheServerHas() {
        role(10L, "Member", true, EnumSet.noneOf(Permission.class));
        when(repository.get(5L)).thenReturn(new AutoroleConfig(5L, true, 60, List.of(10L), List.of()));

        api.save(guild, actor, DataObject.empty().put("enabled", false));

        verify(repository).save(new AutoroleConfig(5L, false, 60, List.of(10L), List.of()));
    }

    @Test
    void aSavedRoleThatWasDeletedLaterIsReportedAsAnIssue() {
        when(repository.get(5L)).thenReturn(new AutoroleConfig(5L, true, 0, List.of(77L), List.of()));

        DataObject result = api.get(guild);

        assertEquals(1, result.getArray("issues").length());
    }

    @Test
    void aRoleToTakeAwayIsSavedEvenIfItIsPowerfulAsLongAsTheBotCanManageIt() {
        role(10L, "Old staff", true, EnumSet.of(Permission.BAN_MEMBERS));

        api.save(guild, actor, DataObject.empty().put("enabled", true).put("memberRemoveRoleIds", ids("10")));

        verify(repository).save(new AutoroleConfig(5L, true, 0, List.of(), List.of(), List.of(10L), List.of()));
    }

    @Test
    void aRoleAboveTheBotCannotBeTakenAway() {
        role(10L, "Boss", false, EnumSet.noneOf(Permission.class));

        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, DataObject.empty().put("memberRemoveRoleIds", ids("10"))));

        assertEquals(400, error.status);
        verify(repository, never()).save(any());
    }

    @Test
    void aRoleCannotBeBothGivenAndTakenAway() {
        role(10L, "Member", true, EnumSet.noneOf(Permission.class));

        var error = assertThrows(TicketAdminApi.ApiError.class, () ->
                api.save(guild, actor, DataObject.empty().put("memberRoleIds", ids("10")).put("memberRemoveRoleIds", ids("10"))));

        assertTrue(error.problems.get(0).contains("both given and taken away"));
    }

    @Test
    void savedRulesWithoutRemovalsStayExactlyAsTheyWere() {
        AutoroleConfig old = new AutoroleConfig(5L, true, 60, List.of(10L), List.of());
        assertTrue(old.memberRemoveRoleIds().isEmpty());
        assertTrue(old.anythingFor(false));
        assertTrue(!old.anythingFor(true));
        assertEquals(List.of(), old.removalsFor(false));
    }
}
