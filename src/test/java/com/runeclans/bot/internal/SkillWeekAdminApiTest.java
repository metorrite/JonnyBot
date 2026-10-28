package com.runeclans.bot.internal;

import com.runeclans.bot.events.SkillWeekRepository;
import com.runeclans.bot.events.SkillWeekService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The dashboard's Skill of the week (T-541): a rota of skills only, and no switching on without the hub. */
class SkillWeekAdminApiTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long ADMIN = Long.parseLong("5".repeat(18));

    private final SkillWeekService service = Mockito.mock(SkillWeekService.class);
    private final SkillWeekRepository repository = Mockito.mock(SkillWeekRepository.class);
    private final SkillWeekAdminApi api = new SkillWeekAdminApi(service, repository);

    private static DataObject body(boolean enabled, Integer... rota) {
        return DataObject.empty().put("enabled", enabled).put("rota", DataArray.fromCollection(List.of(rota)));
    }

    @Test
    void savesTheRotaAndOpensThisWeekWhenSwitchedOn() {
        when(service.available()).thenReturn(true);
        when(service.view(eq(GUILD), Mockito.anyInt())).thenReturn(DataObject.empty());
        api.save(GUILD, ADMIN, body(true, 9, 15));
        verify(repository).save(GUILD, true, List.of(9, 15), ADMIN);
        verify(service).open(eq(GUILD), any());
    }

    @Test
    void refusesBadSkillsAndSwitchingOnWithoutTheHub() {
        when(service.available()).thenReturn(false);
        TicketAdminApi.ApiError noHub = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(GUILD, ADMIN, body(true, 9)));
        assertEquals(400, noHub.status);
        assertThrows(TicketAdminApi.ApiError.class, () -> api.save(GUILD, ADMIN, body(false, 99)));
        verify(repository, never()).save(anyLong(), anyBoolean(), any(), anyLong());
        // off needs no hub
        when(service.view(eq(GUILD), Mockito.anyInt())).thenReturn(DataObject.empty());
        api.save(GUILD, ADMIN, body(false));
        verify(repository).save(GUILD, false, List.of(), ADMIN);
        verify(service, never()).open(anyLong(), any());
    }
}
