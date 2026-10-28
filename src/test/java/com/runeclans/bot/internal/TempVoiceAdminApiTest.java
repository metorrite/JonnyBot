package com.runeclans.bot.internal;

import com.runeclans.bot.tempvoice.TempVoiceRepository;
import com.runeclans.bot.tempvoice.TempVoiceRules.Hub;
import com.runeclans.bot.tempvoice.TempVoiceRules.Temp;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TempVoiceAdminApiTest {
    private TempVoiceRepository repository;
    private TempVoiceAdminApi api;
    private Guild guild;
    private Member actor;
    private SelfMember self;
    private VoiceChannel hubChannel;

    @BeforeEach
    void setUp() {
        repository = mock(TempVoiceRepository.class);
        api = new TempVoiceAdminApi(repository);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(5L);
        self = mock(SelfMember.class);
        when(self.hasPermission(any(Permission[].class))).thenReturn(true);
        when(guild.getSelfMember()).thenReturn(self);
        actor = mock(Member.class);
        when(actor.getId()).thenReturn("7");
        hubChannel = mock(VoiceChannel.class);
        when(hubChannel.getIdLong()).thenReturn(30L);
        when(hubChannel.getName()).thenReturn("Create VC");
        when(guild.getVoiceChannelById(30L)).thenReturn(hubChannel);
        when(repository.hubs(5L)).thenReturn(List.of());
        when(repository.hubByChannel(30L)).thenReturn(Optional.empty());
        when(repository.channel(30L)).thenReturn(Optional.empty());
    }

    private static DataObject body(String channel, String pattern, int limit) {
        return DataObject.empty().put("hubChannelId", channel).put("namePattern", pattern).put("userLimit", limit);
    }

    @Test
    void aVoiceChannelBecomesAHub() {
        api.save(guild, actor, body("30", "{user}'s channel", 4));

        ArgumentCaptor<Hub> saved = ArgumentCaptor.forClass(Hub.class);
        verify(repository).saveHub(saved.capture());
        assertEquals(30L, saved.getValue().hubChannelId());
        assertEquals(4, saved.getValue().userLimit());
        assertEquals("{user}'s channel", saved.getValue().namePattern());
    }

    @Test
    void aMissingChannelABadNameOrAnImpossibleLimitIsRefusedWithEveryProblem() {
        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body("99", "", 500)));
        assertEquals(3, error.problems.size());
        verify(repository, never()).saveHub(any());
    }

    @Test
    void aTemporaryChannelCannotBeAHub() {
        when(repository.channel(30L)).thenReturn(Optional.of(new Temp(30L, 5L, 1L, 2L, false)));
        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body("30", "Lounge", 0)));
        assertTrue(error.problems.get(0).contains("temporary channel itself"));
    }

    @Test
    void aServerWithoutThePermissionsIsToldWhy() {
        when(self.hasPermission(any(Permission[].class))).thenReturn(false);
        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body("30", "Lounge", 0)));
        assertTrue(error.problems.get(0).contains("Manage Channels"));
    }

    @Test
    void thereIsALimitOnHubs() {
        when(repository.hubs(5L)).thenReturn(java.util.stream.IntStream.range(0, 5).mapToObj(i -> new Hub(5L, 100L + i, null, "x", 0)).toList());
        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body("30", "Lounge", 0)));
        assertTrue(error.problems.get(0).contains("at most 5 hubs"));
        // changing one that already exists is fine
        when(repository.hubByChannel(30L)).thenReturn(Optional.of(new Hub(5L, 30L, null, "x", 0)));
        api.save(guild, actor, body("30", "Lounge", 0));
        verify(repository).saveHub(any());
    }

    @Test
    void aCategoryThatIsNotInTheServerIsRefused() {
        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body("30", "Lounge", 0).put("categoryId", "999")));
        assertTrue(error.problems.get(0).contains("category"));
        Category category = mock(Category.class);
        when(category.getIdLong()).thenReturn(50L);
        when(guild.getCategoryById(50L)).thenReturn(category);
        api.save(guild, actor, body("30", "Lounge", 0).put("categoryId", "50"));
        ArgumentCaptor<Hub> saved = ArgumentCaptor.forClass(Hub.class);
        verify(repository).saveHub(saved.capture());
        assertEquals(50L, saved.getValue().categoryId());
    }

    @Test
    void removingAHubThatIsNotOneIsANotFound() {
        when(repository.deleteHub(5L, 31L)).thenReturn(false);
        assertEquals(404, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "DELETE", new String[]{"tempvoice", "31"}, DataObject::empty)).status);
        assertEquals(null, api.route(guild, actor, "GET", new String[]{"other"}, DataObject::empty));
    }
}
