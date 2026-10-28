package com.runeclans.bot.internal;

import com.runeclans.bot.points.PointsExtrasRepository;
import com.runeclans.bot.points.PointsExtrasRules.RoleReward;
import com.runeclans.bot.points.PointsExtrasRules.Trigger;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

class PointsExtrasAdminApiTest {
    private PointsExtrasRepository repository;
    private PointsExtrasAdminApi api;
    private Guild guild;
    private Member actor;
    private SelfMember self;

    @BeforeEach
    void setUp() {
        repository = mock(PointsExtrasRepository.class);
        api = new PointsExtrasAdminApi(repository);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(5L);
        self = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(self);
        actor = mock(Member.class);
        when(actor.getId()).thenReturn("7");
        when(repository.roleRewards(5L)).thenReturn(List.of());
        when(repository.triggers(5L)).thenReturn(List.of());
        when(repository.saveRoleReward(any())).thenReturn(true);
        when(repository.saveTrigger(any())).thenReturn(true);
    }

    private void role(long id, boolean usable, EnumSet<Permission> permissions) {
        Role role = mock(Role.class);
        when(role.getName()).thenReturn("R" + id);
        when(role.getIdLong()).thenReturn(id);
        when(role.getPermissions()).thenReturn(permissions);
        when(self.canInteract(role)).thenReturn(usable);
        when(guild.getRoleById(id)).thenReturn(role);
    }

    @Test
    void aGoodRoleRewardIsSaved() {
        role(10L, true, EnumSet.noneOf(Permission.class));
        api.saveRole(guild, actor, DataObject.empty().put("threshold", 500).put("roleId", "10").put("removeLower", true));
        ArgumentCaptor<RoleReward> saved = ArgumentCaptor.forClass(RoleReward.class);
        verify(repository).saveRoleReward(saved.capture());
        assertEquals(500, saved.getValue().threshold());
        assertEquals(10L, saved.getValue().roleId());
        assertTrue(saved.getValue().removeLower());
    }

    @Test
    void aRoleTheBotCannotHandOutOrOneThatIsPowerfulIsRefused() {
        role(10L, false, EnumSet.noneOf(Permission.class));
        role(11L, true, EnumSet.of(Permission.ADMINISTRATOR));
        for (String id : new String[]{"10", "11", "99", ""}) {
            var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.saveRole(guild, actor, DataObject.empty().put("threshold", 500).put("roleId", id)));
            assertEquals(400, error.status, id);
        }
        verify(repository, never()).saveRoleReward(any());
    }

    @Test
    void aRewardThatAnotherAlreadyUsesTheThresholdOrRoleOfIsRefused() {
        role(10L, true, EnumSet.noneOf(Permission.class));
        when(repository.roleRewards(5L)).thenReturn(List.of(new RoleReward(1, 5, 500, 10, false)));
        var sameThreshold = assertThrows(TicketAdminApi.ApiError.class, () -> api.saveRole(guild, actor, DataObject.empty().put("threshold", 500).put("roleId", "10")));
        assertEquals(2, sameThreshold.problems.size());
        // changing that same reward is fine: it is not counted against itself
        api.saveRole(guild, actor, DataObject.empty().put("id", "1").put("threshold", 500).put("roleId", "10").put("removeLower", true));
        verify(repository).saveRoleReward(any());
    }

    @Test
    void changingARewardThatIsGoneIsANotFound() {
        role(10L, true, EnumSet.noneOf(Permission.class));
        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.saveRole(guild, actor, DataObject.empty().put("id", "4").put("threshold", 5).put("roleId", "10"))).status);
    }

    @Test
    void aGoodTriggerIsSavedWithItsKeywordCleaned() {
        api.saveTrigger(guild, actor, DataObject.empty().put("keyword", "  Thanks ").put("points", 1).put("cooldownSeconds", 60).put("dailyCap", 5).put("enabled", true));
        ArgumentCaptor<Trigger> saved = ArgumentCaptor.forClass(Trigger.class);
        verify(repository).saveTrigger(saved.capture());
        assertEquals("thanks", saved.getValue().keyword());
        assertEquals(60, saved.getValue().cooldownSeconds());
    }

    @Test
    void aBadTriggerIsRefusedWithEveryProblem() {
        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.saveTrigger(guild, actor, DataObject.empty().put("keyword", "x").put("points", 99).put("cooldownSeconds", -5).put("dailyCap", 0)));
        assertEquals(4, error.problems.size());
        verify(repository, never()).saveTrigger(any());
    }

    @Test
    void routesAreOnlyThisOnes() {
        assertEquals(null, api.route(guild, actor, "GET", new String[]{"points"}, DataObject::empty));
        assertEquals(null, api.route(guild, actor, "GET", new String[]{"points", "rewards"}, DataObject::empty));
        assertEquals(405, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"pointskeywords"}, DataObject::empty)).status);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "DELETE", new String[]{"pointsroles", "abc"}, DataObject::empty)).status);
    }
}
