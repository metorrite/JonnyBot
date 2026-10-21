package com.younglings.bot.internal;

import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.rolerules.RoleRuleRepository;
import com.younglings.bot.rolerules.RoleRuleRules.Event;
import com.younglings.bot.rolerules.RoleRuleRules.Rule;
import com.younglings.bot.rolerules.RoleRuleService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleRuleAdminApiTest {
    private static final long GUILD = 10;

    private RoleRuleRepository repository;
    private RoleRuleService service;
    private DashboardAccess access;
    private RoleRuleAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        repository = mock(RoleRuleRepository.class);
        service = mock(RoleRuleService.class);
        access = mock(DashboardAccess.class);
        api = new RoleRuleAdminApi(repository, service, access);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        actor = mock(Member.class);
        when(access.tierOf(guild, actor)).thenReturn(DashboardAccess.Tier.ADMIN);
        when(repository.list(GUILD)).thenReturn(List.of());
    }

    private static DataObject body() {
        return DataObject.empty().put("name", "Chat access").put("event", "GAINS").put("enabled", true)
                .put("when", DataArray.empty().add("1")).put("add", DataArray.empty().add("20")).put("remove", DataArray.empty());
    }

    @Test
    void aDeveloperOrAnyoneWhoIsNotAnAdminIsRefusedOnEveryRoute() {
        when(access.tierOf(guild, actor)).thenReturn(DashboardAccess.Tier.DEVELOPER);

        for (String[] call : new String[][]{{"GET", "role-rules"}, {"POST", "role-rules"}, {"PUT", "role-rules", "3"}, {"DELETE", "role-rules", "3"}}) {
            String[] parts = java.util.Arrays.copyOfRange(call, 1, call.length);
            assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, call[0], parts, RoleRuleAdminApiTest::body)).status);
        }
        verify(repository, never()).create(any());
        verify(repository, never()).save(any());
        verify(repository, never()).delete(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void aRuleThatFailsTheServiceChecksIsNeverSaved() {
        when(service.problemWith(any(), any(), anyList())).thenReturn("These rules would loop");

        var thrown = assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"role-rules"}, RoleRuleAdminApiTest::body));

        assertEquals(400, thrown.status);
        verify(repository, never()).create(any());
        verify(service, never()).changed(GUILD);
    }

    @Test
    void aRulePassingTheChecksIsSavedAndTheCacheForgotten() {
        when(service.problemWith(any(), any(), anyList())).thenReturn(null);
        when(repository.create(any())).thenAnswer(call -> call.getArgument(0));

        api.route(guild, actor, "POST", new String[]{"role-rules"}, RoleRuleAdminApiTest::body);

        verify(repository).create(org.mockito.ArgumentMatchers.argThat((Rule r) -> r.guildId() == GUILD && r.event() == Event.GAINS && r.when().equals(List.of(1L)) && r.add().equals(List.of(20L))));
        verify(service).changed(GUILD);
    }

    @Test
    void aRuleOfAnotherServerCannotBeEditedOrDeleted() {
        when(repository.delete(GUILD, 9)).thenReturn(false);

        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "PUT", new String[]{"role-rules", "9"}, RoleRuleAdminApiTest::body)).status);
        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "DELETE", new String[]{"role-rules", "9"}, DataObject::empty)).status);
    }
}
