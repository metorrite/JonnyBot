package com.younglings.bot.internal;

import com.younglings.bot.access.AccessArea;
import com.younglings.bot.access.AccessGrantRepository;
import com.younglings.bot.access.AccessGrantService;
import com.younglings.bot.access.AccessLevel;
import com.younglings.bot.notice.BotOwners;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessGrantAdminApiTest {
    private AccessGrantRepository repository;
    private AccessGrantService service;
    private BotOwners owners;
    private AccessGrantAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        repository = mock(AccessGrantRepository.class);
        service = mock(AccessGrantService.class);
        owners = mock(BotOwners.class);
        api = new AccessGrantAdminApi(repository, service, owners);
        guild = mock(Guild.class);
        when(guild.getJDA()).thenReturn(mock(JDA.class));
        actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(7L);
        when(owners.isOwner(any(), anyLong())).thenReturn(true);
        when(repository.all()).thenReturn(List.of());
    }

    private static DataObject userGrant() {
        return DataObject.empty().put("area", "hub").put("subject", "user").put("userId", "577853260153618443").put("note", "tester");
    }

    @Test
    void theOwnerCanLetAUserInAndTheRememberedAnswersAreForgotten() {
        api.route(guild, actor, "POST", new String[]{"access-grants"}, AccessGrantAdminApiTest::userGrant);

        verify(repository).add(AccessArea.HUB, 577853260153618443L, null, null, "tester", 7L, null);
        verify(service).listChanged();
    }

    @Test
    void aUserGrantNamesThePersonWhenTheBotKnowsThem() {
        long known = Long.parseLong("4".repeat(18));
        long unknown = Long.parseLong("6".repeat(18));
        java.time.Instant at = java.time.Instant.parse("2026-10-23T00:00:00Z");
        when(repository.all()).thenReturn(List.of(
                new com.younglings.bot.access.AccessGrant(1, AccessArea.DASHBOARD, known, null, null, "", 7L, at, null),
                new com.younglings.bot.access.AccessGrant(2, AccessArea.DASHBOARD, unknown, null, null, "", 7L, at, null)));
        net.dv8tion.jda.api.entities.User user = mock(net.dv8tion.jda.api.entities.User.class);
        when(user.getName()).thenReturn("tester");
        when(user.getEffectiveAvatarUrl()).thenReturn("https://cdn.example/avatar.png");
        when(guild.getJDA().getUserById(known)).thenReturn(user);

        var grants = api.list(guild).getArray("grants");

        assertEquals("tester", grants.getObject(0).getString("userName"));
        assertEquals("https://cdn.example/avatar.png", grants.getObject(0).getString("avatarUrl"));
        assertNull(grants.getObject(1).getString("userName", null), "Discord could not say: the id alone");
    }

    @Test
    void theOwnerCanLetAServerInAtALevel() {
        DataObject body = DataObject.empty().put("area", "dashboard").put("subject", "server").put("guildId", "1388208054008545300").put("level", "manage_server");

        api.route(guild, actor, "POST", new String[]{"access-grants"}, () -> body);

        verify(repository).add(AccessArea.DASHBOARD, null, 1388208054008545300L, AccessLevel.MANAGE_SERVER, "", 7L, null);
    }

    @Test
    void anAdminWhoIsNotTheOwnerCannotSeeAddOrRemoveAnything() {
        when(owners.isOwner(any(), anyLong())).thenReturn(false);

        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "GET", new String[]{"access-grants"}, DataObject::empty)).status);
        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"access-grants"}, AccessGrantAdminApiTest::userGrant)).status);
        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "DELETE", new String[]{"access-grants", "1"}, DataObject::empty)).status);
        verify(repository, never()).add(any(), any(), any(), any(), anyString(), anyLong(), any());
        verify(repository, never()).delete(anyLong());
    }

    @Test
    void aBadAreaIdLevelOrDateIsRefused() {
        DataObject badArea = userGrant().put("area", "nope");
        DataObject badUser = userGrant().put("userId", "12");
        DataObject noLevel = DataObject.empty().put("area", "hub").put("subject", "server").put("guildId", "1388208054008545300");
        DataObject pastDate = userGrant().put("expiresOn", "2020-01-01");
        DataObject noSubject = DataObject.empty().put("area", "hub");

        for (DataObject body : List.of(badArea, badUser, noLevel, pastDate, noSubject)) {
            assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "POST", new String[]{"access-grants"}, () -> body)).status);
        }
        verify(repository, never()).add(any(), any(), any(), any(), anyString(), anyLong(), any());
    }

    @Test
    void removingAnEntryThatIsNotThereIsANotFound() {
        when(repository.delete(eq(9L))).thenReturn(false);

        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "DELETE", new String[]{"access-grants", "9"}, DataObject::empty)).status);
    }

    @Test
    void otherRoutesAreNotThisExtensions() {
        assertNull(api.route(guild, actor, "GET", new String[]{"beta-guilds"}, DataObject::empty));
    }
}
