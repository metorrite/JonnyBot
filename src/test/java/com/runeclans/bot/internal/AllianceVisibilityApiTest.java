package com.runeclans.bot.internal;

import com.runeclans.bot.alliance.AllianceRepository;
import com.runeclans.bot.alliance.AllianceVisibility.Visibility;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.clanprofile.ClanProfileService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The visibility choice on the alliances route: hidden by default, three valid values, nothing else saved. */
class AllianceVisibilityApiTest {
    private AllianceRepository alliances;
    private AllianceAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        alliances = mock(AllianceRepository.class);
        api = new AllianceAdminApi(alliances, mock(ClanProfileRepository.class), mock(ClanProfileService.class));
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(5L);
        actor = mock(Member.class);
        when(actor.getId()).thenReturn("7");
        when(alliances.alliancesOf(5L)).thenReturn(List.of());
        when(alliances.visibility(5L)).thenReturn(Visibility.HIDDEN);
    }

    @Test
    void theListSaysWhatTheClanPageShowsAndItIsHiddenByDefault() {
        assertEquals("HIDDEN", api.list(guild).getString("visibility"));
    }

    @Test
    void eachValidChoiceIsSaved() {
        for (String choice : new String[]{"SHOWN", "NO_ALLIES", "HIDDEN", "shown"}) {
            api.route(guild, actor, "PUT", new String[]{"alliances", "visibility"}, () -> DataObject.empty().put("visibility", choice));
        }
        verify(alliances).saveVisibility(5L, Visibility.NO_ALLIES);
        verify(alliances, org.mockito.Mockito.times(2)).saveVisibility(5L, Visibility.SHOWN);
        verify(alliances).saveVisibility(5L, Visibility.HIDDEN);
    }

    @Test
    void anythingElseIsRefusedAndSavesNothing() {
        for (String bad : new String[]{"", "ALL", "public", "null"}) {
            assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "PUT", new String[]{"alliances", "visibility"}, () -> DataObject.empty().put("visibility", bad))).status, bad);
        }
        verify(alliances, never()).saveVisibility(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void onlyPutIsAllowed() {
        assertEquals(405, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "GET", new String[]{"alliances", "visibility"}, DataObject::empty)).status);
    }
}
