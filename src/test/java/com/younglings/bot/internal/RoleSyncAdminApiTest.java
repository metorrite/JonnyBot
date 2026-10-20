package com.younglings.bot.internal;

import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.runescape.RoleSyncBackfill;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleSyncAdminApiTest {
    private RoleSyncBackfill backfill;
    private DashboardAccess access;
    private RoleSyncAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        backfill = mock(RoleSyncBackfill.class);
        access = mock(DashboardAccess.class);
        api = new RoleSyncAdminApi(backfill, access);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(10L);
        actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(7L);
        when(access.tierOf(guild, actor)).thenReturn(DashboardAccess.Tier.ADMIN);
    }

    @Test
    void aDeveloperOrAnyoneWhoIsNotAnAdminIsRefusedOnEveryRoute() {
        when(access.tierOf(guild, actor)).thenReturn(DashboardAccess.Tier.DEVELOPER);

        for (String[] call : new String[][]{{"GET", "role-sync"}, {"POST", "role-sync", "plan"}, {"POST", "role-sync", "apply"}}) {
            String[] parts = java.util.Arrays.copyOfRange(call, 1, call.length);
            assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, call[0], parts, DataObject::empty)).status);
        }
        verify(backfill, never()).plan(any());
        verify(backfill, never()).start(any(), anySet(), anyLong());
    }

    @Test
    void theDryRunRouteOnlyPlansItNeverStarts() {
        when(backfill.plan(guild)).thenReturn(new RoleSyncBackfill.Plan(List.of(), 3, 0, null));

        DataObject answer = api.route(guild, actor, "POST", new String[]{"role-sync", "plan"}, DataObject::empty);

        assertEquals(3, answer.getInt("linkedAccounts"));
        verify(backfill, never()).start(any(), anySet(), anyLong());
    }

    @Test
    void applyingPassesExactlyTheSelectedIdsAndTheActor() {
        when(backfill.start(guild, Set.of(5L, 6L), 7L)).thenReturn(null);
        DataObject body = DataObject.empty().put("userIds", DataArray.empty().add("5").add("6"));

        api.route(guild, actor, "POST", new String[]{"role-sync", "apply"}, () -> body);

        verify(backfill).start(guild, Set.of(5L, 6L), 7L);
    }

    @Test
    void aRefusalFromTheBackfillComesBackAsAConflict() {
        when(backfill.start(any(), anySet(), anyLong())).thenReturn("A fix is already running in this server.");
        DataObject body = DataObject.empty().put("userIds", DataArray.empty().add("5"));

        assertEquals(409, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"role-sync", "apply"}, () -> body)).status);
        assertNull(null);
    }
}
