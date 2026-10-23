package com.younglings.bot.internal;

import com.younglings.bot.access.AccessLevel;
import com.younglings.bot.beta.BetaAccessService;
import com.younglings.bot.beta.BetaGuildRepository;
import com.younglings.bot.notice.BotOwners;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BetaAdminApiTest {
    private BetaGuildRepository repository;
    private BetaAccessService access;
    private BotOwners owners;
    private BetaAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        repository = mock(BetaGuildRepository.class);
        access = mock(BetaAccessService.class);
        owners = mock(BotOwners.class);
        api = new BetaAdminApi(repository, access, owners);
        guild = mock(Guild.class);
        JDA jda = mock(JDA.class);
        when(guild.getJDA()).thenReturn(jda);
        actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(7L);
        when(owners.isOwner(any(), anyLong())).thenReturn(true);
        when(repository.all()).thenReturn(List.of(new BetaGuildRepository.BetaGuild(1388208054008545300L, "RS Noobs", AccessLevel.MANAGE_SERVER)));
    }

    @Test
    void theListShowsEachServerAndWhetherThePersonMayChangeIt() {
        DataObject result = api.list(guild, actor);

        assertEquals("1388208054008545300", result.getArray("guilds").getObject(0).getString("guildId"));
        assertEquals("RS Noobs", result.getArray("guilds").getObject(0).getString("label"));
        assertFalse(result.getArray("guilds").getObject(0).getBoolean("botPresent"));
        assertTrue(result.getBoolean("canEdit"));
    }

    @Test
    void theOwnerCanAddOrRenameAServerAndTheRememberedAnswersAreForgotten() {
        api.save(guild, actor, "577853260153618443", DataObject.empty().put("label", "  Koalafied  "));

        verify(repository).upsert(577853260153618443L, "Koalafied", AccessLevel.ADMINISTRATOR, 7L);
        verify(access).listChanged();
    }

    @Test
    void aNewServerStartsAtAdministratorAndTheListOffersTheThreeLevels() {
        long id = Long.parseLong("5".repeat(18));
        api.save(guild, actor, Long.toString(id), DataObject.empty());
        verify(repository).upsert(id, "", AccessLevel.ADMINISTRATOR, 7L);

        DataObject list = api.list(guild, actor);
        assertEquals("administrator", list.getString("defaultLevel"));
        assertEquals(3, list.getArray("levels").length());
        assertEquals("owner", list.getArray("levels").getObject(0).getString("key"));
        assertEquals("manage_server", list.getArray("guilds").getObject(0).getString("level"));
    }

    @Test
    void aLabelChangeKeepsTheServersLevelAndAChosenLevelIsSaved() {
        api.save(guild, actor, "1388208054008545300", DataObject.empty().put("label", "Noobs"));
        verify(repository).upsert(1388208054008545300L, "Noobs", AccessLevel.MANAGE_SERVER, 7L);

        api.save(guild, actor, "1388208054008545300", DataObject.empty().put("label", "Noobs").put("level", "owner"));
        verify(repository).upsert(1388208054008545300L, "Noobs", AccessLevel.OWNER, 7L);
    }

    @Test
    void aBetaServerCannotLetInEveryMember() {
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class,
                () -> api.save(guild, actor, "1388208054008545300", DataObject.empty().put("level", "member"))).status);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class,
                () -> api.save(guild, actor, "1388208054008545300", DataObject.empty().put("level", "nonsense"))).status);
        verify(repository, never()).upsert(anyLong(), anyString(), any(), anyLong());
    }

    @Test
    void anAdminWhoIsNotTheOwnerSeesAnEmptyListAndCannotChangeIt() {
        when(owners.isOwner(any(), anyLong())).thenReturn(false);

        DataObject list = api.list(guild, actor);
        assertFalse(list.getBoolean("canEdit"));
        assertEquals(0, list.getArray("guilds").length());
        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, "577853260153618443", DataObject.empty())).status);
        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.remove(guild, actor, "577853260153618443")).status);
        verify(repository, never()).upsert(anyLong(), anyString(), any(), anyLong());
        verify(repository, never()).delete(anyLong());
    }

    @Test
    void aServerIdMustLookLikeOne() {
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, "abc", DataObject.empty())).status);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, "123", DataObject.empty())).status);
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, "577853260153618443", DataObject.empty().put("label", "x".repeat(61)))).status);
    }

    @Test
    void removingAServerNotOnTheListIsANotFound() {
        when(repository.delete(1062157106347786240L)).thenReturn(false);

        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.remove(guild, actor, "1062157106347786240")).status);
    }

    @Test
    void removingOneTakesItOffAndForgetsRememberedAnswers() {
        when(repository.delete(1388208054008545300L)).thenReturn(true);

        api.remove(guild, actor, "1388208054008545300");

        verify(access).listChanged();
    }
}
