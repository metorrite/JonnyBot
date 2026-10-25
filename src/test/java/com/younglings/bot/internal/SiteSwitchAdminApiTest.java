package com.younglings.bot.internal;

import com.younglings.bot.access.GateSource;
import com.younglings.bot.notice.BotOwners;
import com.younglings.bot.siteaccess.SiteSwitchRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SiteSwitchAdminApiTest {
    private SiteSwitchRepository repository;
    private BotOwners owners;
    private SiteSwitchAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        repository = mock(SiteSwitchRepository.class);
        owners = mock(BotOwners.class);
        api = new SiteSwitchAdminApi(repository, owners, GateSource.ownTables(null, null, repository::all));
        guild = mock(Guild.class);
        when(guild.getJDA()).thenReturn(mock(JDA.class));
        actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(7L);
        when(owners.isOwner(any(), anyLong())).thenReturn(true);
        when(repository.all()).thenReturn(Map.of("hub", true));
    }

    @Test
    void aSwitchNeverSetIsClosedAndAStoredOneIsReadBack() {
        DataObject states = api.states().getObject("switches");

        assertFalse(states.getBoolean("jonnybot"));
        assertTrue(states.getBoolean("hub"));
        assertFalse(states.getBoolean("dashboard"));
    }

    @Test
    void theOwnerCanTurnASwitchOnAndItIsRecordedWithWhoDidIt() {
        api.route(guild, actor, "PUT", new String[]{"site-switches", "dashboard"}, () -> DataObject.empty().put("enabled", true));

        verify(repository).set("dashboard", true, 7L);
    }

    @Test
    void anAdminWhoIsNotTheOwnerIsRefusedOnEveryRouteAndNothingChanges() {
        when(owners.isOwner(any(), anyLong())).thenReturn(false);

        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "GET", new String[]{"site-switches"}, DataObject::empty)).status);
        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class,
                () -> api.route(guild, actor, "PUT", new String[]{"site-switches", "hub"}, () -> DataObject.empty().put("enabled", false))).status);
        verify(repository, never()).set(anyString(), anyBoolean(), anyLong());
    }

    @Test
    void anUnknownSwitchOrAMissingValueIsRefused() {
        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class,
                () -> api.route(guild, actor, "PUT", new String[]{"site-switches", "nope"}, () -> DataObject.empty().put("enabled", true))).status);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class,
                () -> api.route(guild, actor, "PUT", new String[]{"site-switches", "hub"}, DataObject::empty)).status);
    }

    @Test
    void otherRoutesAreNotThisExtensionsAndWrongMethodsAreRefused() {
        assertEquals(null, api.route(guild, actor, "GET", new String[]{"premium-guilds"}, DataObject::empty));
        assertEquals(405, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "DELETE", new String[]{"site-switches"}, DataObject::empty)).status);
    }
}
