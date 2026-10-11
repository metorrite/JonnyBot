package com.younglings.bot.internal;

import com.younglings.bot.notice.BotOwners;
import com.younglings.bot.premium.PremiumGuildRepository;
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

class PremiumAdminApiTest {
    private PremiumGuildRepository repository;
    private BotOwners owners;
    private PremiumAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        repository = mock(PremiumGuildRepository.class);
        owners = mock(BotOwners.class);
        api = new PremiumAdminApi(repository, owners);
        guild = mock(Guild.class);
        when(guild.getJDA()).thenReturn(mock(JDA.class));
        when(guild.getIdLong()).thenReturn(5L);
        actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(7L);
        when(owners.isOwner(any(), anyLong())).thenReturn(true);
        when(repository.all()).thenReturn(List.of(new PremiumGuildRepository.PremiumGuild(1388208054008545300L, "RS Noobs")));
    }

    @Test
    void theOwnerSeesTheListAndMayChangeIt() {
        DataObject result = api.list(guild, actor);

        assertEquals(1, result.getArray("guilds").length());
        assertTrue(result.getBoolean("canEdit"));
    }

    @Test
    void anAdminWhoIsNotTheOwnerSeesNoListOnlyWhetherThisServerIsPremium() {
        when(owners.isOwner(any(), anyLong())).thenReturn(false);
        when(repository.isPremium(5L)).thenReturn(true);

        DataObject result = api.list(guild, actor);

        assertEquals(0, result.getArray("guilds").length());
        assertFalse(result.getBoolean("canEdit"));
        assertTrue(result.getBoolean("thisServer"));
    }

    @Test
    void onlyTheOwnerCanChangeIt() {
        when(owners.isOwner(any(), anyLong())).thenReturn(false);

        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, "577853260153618443", DataObject.empty())).status);
        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.remove(guild, actor, "577853260153618443")).status);
        verify(repository, never()).upsert(anyLong(), anyString(), anyLong());
        verify(repository, never()).delete(anyLong());
    }

    @Test
    void theOwnerCanAddAServerWithATidyLabel() {
        api.save(guild, actor, "577853260153618443", DataObject.empty().put("label", "  Koalafied  "));

        verify(repository).upsert(577853260153618443L, "Koalafied", 7L);
    }

    @Test
    void aServerIdMustLookLikeOne() {
        assertEquals(400, assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, "abc", DataObject.empty())).status);
    }

    @Test
    void removingAServerNotOnThePlanIsANotFound() {
        when(repository.delete(1062157106347786240L)).thenReturn(false);

        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.remove(guild, actor, "1062157106347786240")).status);
    }
}
