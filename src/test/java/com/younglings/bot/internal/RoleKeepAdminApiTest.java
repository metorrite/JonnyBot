package com.younglings.bot.internal;

import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.rolekeep.RoleKeepRepository;
import com.younglings.bot.rolekeep.RoleKeepService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleKeepAdminApiTest {
    @Test
    void aDeveloperOrAnyoneWhoIsNotAnAdminIsRefusedOnEveryRoute() {
        RoleKeepRepository repository = mock(RoleKeepRepository.class);
        RoleKeepService service = mock(RoleKeepService.class);
        DashboardAccess access = mock(DashboardAccess.class);
        RoleKeepAdminApi api = new RoleKeepAdminApi(repository, service, access);
        Guild guild = mock(Guild.class);
        Member actor = mock(Member.class);
        when(access.tierOf(guild, actor)).thenReturn(DashboardAccess.Tier.DEVELOPER);

        for (String[] call : new String[][]{{"GET", "role-keeper"}, {"PUT", "role-keeper", "sticky"}, {"POST", "role-keeper", "kept"}, {"POST", "role-keeper", "temp"}, {"DELETE", "role-keeper", "temp", "3"}}) {
            String[] parts = java.util.Arrays.copyOfRange(call, 1, call.length);
            assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, call[0], parts, DataObject::empty)).status);
        }
        verify(service, never()).saveSticky(any(), any());
        verify(service, never()).persist(any(), anyLong(), any(), any());
    }
}
