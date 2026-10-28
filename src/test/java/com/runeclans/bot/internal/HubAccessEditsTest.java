package com.runeclans.bot.internal;

import com.runeclans.bot.access.AccessGrantRepository;
import com.runeclans.bot.access.AccessGrantService;
import com.runeclans.bot.access.GateSource;
import com.runeclans.bot.beta.BetaAccessService;
import com.runeclans.bot.beta.BetaGuildRepository;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.siteaccess.SiteSwitchRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** With the gates read from the RuneClans hub, the bot's own editors refuse every change (409) and save nothing; reading still works. */
class HubAccessEditsTest {
    private static final String USER = "5".repeat(18);
    private static final String SERVER = "6".repeat(18);

    private Guild guild;
    private Member actor;
    private BotOwners owners;

    @BeforeEach
    void setUp() {
        guild = mock(Guild.class);
        when(guild.getJDA()).thenReturn(mock(JDA.class));
        actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(7L);
        owners = mock(BotOwners.class);
        when(owners.isOwner(any(), anyLong())).thenReturn(true);
    }

    private static void refused(Executable change) {
        ApiError e = assertThrows(ApiError.class, change);
        assertEquals(409, e.status);
        assertEquals(HubAccessEdits.MOVED, e.getMessage());
    }

    @Test
    void offNothingIsRefused() {
        assertDoesNotThrow(() -> HubAccessEdits.refuseWhenInHub(false));
        refused(() -> HubAccessEdits.refuseWhenInHub(true));
    }

    @Test
    void betaServersCanBeReadButNotChanged() {
        BetaGuildRepository repository = mock(BetaGuildRepository.class);
        when(repository.all()).thenReturn(List.of());
        BetaAccessService access = mock(BetaAccessService.class);
        when(access.editedInHub()).thenReturn(true);
        BetaAdminApi api = new BetaAdminApi(repository, access, owners);

        refused(() -> api.route(guild, actor, "PUT", new String[]{"beta-guilds", SERVER}, DataObject::empty));
        refused(() -> api.route(guild, actor, "DELETE", new String[]{"beta-guilds", SERVER}, DataObject::empty));
        assertDoesNotThrow(() -> api.route(guild, actor, "GET", new String[]{"beta-guilds"}, DataObject::empty));
        verify(repository, never()).upsert(anyLong(), anyString(), any(), anyLong());
        verify(repository, never()).delete(anyLong());
    }

    @Test
    void theAccessListCanBeReadButNotChanged() {
        AccessGrantRepository repository = mock(AccessGrantRepository.class);
        when(repository.all()).thenReturn(List.of());
        AccessGrantService service = mock(AccessGrantService.class);
        when(service.editedInHub()).thenReturn(true);
        AccessGrantAdminApi api = new AccessGrantAdminApi(repository, service, owners);

        refused(() -> api.route(guild, actor, "POST", new String[]{"access-grants"},
                () -> DataObject.empty().put("area", "hub").put("subject", "user").put("userId", USER).put("note", "")));
        refused(() -> api.route(guild, actor, "DELETE", new String[]{"access-grants", "1"}, DataObject::empty));
        assertDoesNotThrow(() -> api.route(guild, actor, "GET", new String[]{"access-grants"}, DataObject::empty));
        verify(repository, never()).add(any(), any(), any(), any(), anyString(), anyLong(), any());
        verify(repository, never()).delete(anyLong());
    }

    @Test
    void betaTestersCanBeReadButNotChanged() {
        AccessGrantRepository repository = mock(AccessGrantRepository.class);
        when(repository.all()).thenReturn(List.of());
        AccessGrantService service = mock(AccessGrantService.class);
        when(service.editedInHub()).thenReturn(true);
        SiteBuilderBetaAdminApi api = new SiteBuilderBetaAdminApi(repository, service, owners);

        refused(() -> api.route(guild, actor, "PUT", new String[]{"beta-testers", USER}, () -> DataObject.empty().put("note", "x")));
        refused(() -> api.route(guild, actor, "DELETE", new String[]{"beta-testers", USER}, DataObject::empty));
        refused(() -> api.route(guild, actor, "POST", new String[]{"site-builder-beta"}, () -> DataObject.empty().put("userId", USER)));
        refused(() -> api.route(guild, actor, "DELETE", new String[]{"site-builder-beta", USER}, DataObject::empty));
        assertDoesNotThrow(() -> api.route(guild, actor, "GET", new String[]{"beta-testers"}, DataObject::empty));
        verify(repository, never()).add(any(), any(), any(), any(), anyString(), anyLong(), any());
        verify(repository, never()).delete(anyLong());
    }

    @Test
    void siteSwitchesCanBeReadButNotChanged() {
        SiteSwitchRepository repository = mock(SiteSwitchRepository.class);
        when(repository.all()).thenReturn(Map.of());
        GateSource gates = mock(GateSource.class);
        when(gates.fromHub()).thenReturn(true);
        when(gates.switches()).thenReturn(Map.of("hub", true));
        SiteSwitchAdminApi api = new SiteSwitchAdminApi(repository, owners, gates);

        refused(() -> api.route(guild, actor, "PUT", new String[]{"site-switches", "hub"}, () -> DataObject.empty().put("enabled", false)));
        verify(repository, never()).set(anyString(), anyBoolean(), anyLong());
        // what the website's gates read comes from the hub
        assertEquals(true, api.states().getObject("switches").getBoolean("hub"));
    }

    @Test
    void withTheSwitchOffTheEditorsStillSave() {
        SiteSwitchRepository repository = mock(SiteSwitchRepository.class);
        when(repository.all()).thenReturn(Map.of());
        SiteSwitchAdminApi api = new SiteSwitchAdminApi(repository, owners, GateSource.ownTables(null, null, repository::all));

        api.route(guild, actor, "PUT", new String[]{"site-switches", "hub"}, () -> DataObject.empty().put("enabled", true));
        verify(repository).set("hub", true, 7L);
    }
}
