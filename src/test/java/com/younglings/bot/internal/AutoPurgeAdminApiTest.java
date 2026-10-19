package com.younglings.bot.internal;

import com.younglings.bot.autopurge.AutoPurgeRepository;
import com.younglings.bot.autopurge.AutoPurgeRule;
import com.younglings.bot.autopurge.AutoPurgeService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoPurgeAdminApiTest {
    private static final long GUILD = 10, CHANNEL = 100;
    private AutoPurgeRepository repository;
    private AutoPurgeService service;
    private AutoPurgeAdminApi api;
    private Guild guild;
    private Member actor;
    private GuildMessageChannel channel;

    @BeforeEach
    void setUp() {
        repository = mock(AutoPurgeRepository.class);
        service = mock(AutoPurgeService.class);
        api = new AutoPurgeAdminApi(repository, service);
        guild = mock(Guild.class);
        actor = mock(Member.class);
        channel = mock(GuildMessageChannel.class);
        SelfMember self = mock(SelfMember.class);
        when(self.hasPermission(any(GuildMessageChannel.class), any(Permission[].class))).thenReturn(true);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getSelfMember()).thenReturn(self);
        when(guild.getChannelById(GuildMessageChannel.class, CHANNEL)).thenReturn(channel);
        when(channel.getGuild()).thenReturn(guild);
        when(channel.getIdLong()).thenReturn(CHANNEL);
        when(channel.getName()).thenReturn("spam");
        when(actor.getId()).thenReturn("5");
        when(repository.forGuild(GUILD)).thenReturn(List.of());
    }

    private DataObject call(String method, String json, String... parts) {
        return api.route(guild, actor, method, parts, () -> DataObject.fromJson(json));
    }

    @Test
    void aFilteredRuleIsSavedWithItsFilters() {
        call("PUT", "{\"channelId\":\"100\",\"maxAgeMinutes\":1440,\"keepPinned\":true,\"authors\":\"only_bots\",\"onlyWithAttachments\":true,\"containsText\":\" nitro \"}", "autopurge");
        ArgumentCaptor<AutoPurgeRule> saved = ArgumentCaptor.forClass(AutoPurgeRule.class);
        verify(repository).save(saved.capture());
        assertEquals(AutoPurgeRule.Authors.ONLY_BOTS, saved.getValue().authors());
        assertTrue(saved.getValue().onlyWithAttachments());
        assertEquals("nitro", saved.getValue().containsText());
    }

    @Test
    void aRuleWithoutFiltersKeepsTheOldMeaning() {
        call("PUT", "{\"channelId\":\"100\",\"maxAgeMinutes\":1440}", "autopurge");
        ArgumentCaptor<AutoPurgeRule> saved = ArgumentCaptor.forClass(AutoPurgeRule.class);
        verify(repository).save(saved.capture());
        assertEquals(false, saved.getValue().filtered());
    }

    @Test
    void badFiltersRefuseTheSave() {
        assertThrows(TicketAdminApi.ApiError.class, () -> call("PUT", "{\"channelId\":\"100\",\"maxAgeMinutes\":1440,\"authors\":\"everybody\"}", "autopurge"));
        assertThrows(TicketAdminApi.ApiError.class, () -> call("PUT", "{\"channelId\":\"100\",\"maxAgeMinutes\":1440,\"containsText\":\"" + "x".repeat(101) + "\"}", "autopurge"));
        verify(repository, never()).save(any());
    }

    @Test
    void thePreviewReportsTheCountAndSavesAndDeletesNothing() {
        when(service.scan(eq(channel), any(AutoPurgeRule.class), any(Instant.class), eq(false))).thenReturn(new AutoPurgeService.Scan(7, 120, false));
        DataObject out = call("POST", "{\"channelId\":\"100\",\"maxAgeMinutes\":1440,\"authors\":\"ONLY_BOTS\"}", "autopurge", "preview");

        assertEquals(7, out.getInt("matched"));
        assertEquals(120, out.getInt("scanned"));
        assertEquals("spam", out.getString("channelName"));
        verify(service).scan(eq(channel), any(AutoPurgeRule.class), any(Instant.class), eq(false));
        verify(repository, never()).save(any());
    }

    @Test
    void thePreviewChecksTheRuleLikeASaveDoes() {
        assertThrows(TicketAdminApi.ApiError.class, () -> call("POST", "{\"channelId\":\"999\",\"maxAgeMinutes\":1440}", "autopurge", "preview"));
        assertThrows(TicketAdminApi.ApiError.class, () -> call("POST", "{\"channelId\":\"100\",\"maxAgeMinutes\":5}", "autopurge", "preview"));
        verify(service, never()).scan(any(), any(), any(), eq(false));
    }
}
