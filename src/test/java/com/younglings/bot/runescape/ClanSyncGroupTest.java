package com.younglings.bot.runescape;

import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.runescape.polling.PollCoordinator;
import com.younglings.bot.runescape.polling.PollRequest;
import com.younglings.bot.tracking.TrackingEventRouter;
import com.younglings.bot.tracking.TrackingIconCatalog;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A clan set up in two servers is fetched and polled once a day, and each server still ends up as if it had synced alone. */
class ClanSyncGroupTest {
    private RuneScapeApiClient apiClient;
    private ClanMemberRepository clanMembers;
    private PollCoordinator coordinator;
    private ClanSyncService service;
    private GuildSettingsService settings;
    private Guild one;
    private Guild two;

    @BeforeEach
    void setUp() {
        apiClient = mock(RuneScapeApiClient.class);
        clanMembers = mock(ClanMemberRepository.class);
        coordinator = mock(PollCoordinator.class);
        settings = mock(GuildSettingsService.class);
        one = guild(1L, "Younglings");
        two = guild(2L, "  younglings ");

        when(apiClient.fetchClanRoster(anyString())).thenReturn(List.of(member("A"), member("B"), member("New")));
        when(clanMembers.getAll(1L, true)).thenReturn(List.of(row(1L, "A"), row(1L, "B")));
        when(clanMembers.getAll(2L, true)).thenReturn(List.of(row(2L, "A"), row(2L, "B")));
        when(coordinator.submitAll(any())).thenReturn(List.<CompletableFuture<com.younglings.bot.runescape.polling.PollOutcome>>of());
        when(coordinator.awaitAll(any(), any())).thenReturn(List.of());

        service = new ClanSyncService(apiClient, clanMembers, coordinator, mock(RsnRenameService.class), settings,
                mock(TrackingEventRouter.class), mock(WeeklyDigestRepository.class), mock(TrackingIconCatalog.class),
                mock(VerificationRoleSyncService.class), mock(PlayerLinkService.class));
    }

    private Guild guild(long id, String clan) {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(id);
        when(settings.getEffective(id)).thenReturn(new GuildSettings(id, clan, null, null, null, null, null, null, null, true, clan, null, null));
        return guild;
    }

    private static ClanMemberRepository.ClanMemberRow row(long guildId, String rsn) {
        return new ClanMemberRepository.ClanMemberRow(0, guildId, rsn, "Recruit", null, null, true, 0, 0, null);
    }

    private static RuneScapeApiClient.ClanMember member(String rsn) {
        return new RuneScapeApiClient.ClanMember(rsn, "Recruit", 1_000, 0);
    }

    @Test
    void twoServersOfOneClanShareOneFetchAndOnePollPerPlayer() {
        Map<Long, ClanSyncService.SyncResult> results = service.syncAndPollGroup(List.of(one, two));

        verify(apiClient, times(1)).fetchClanRoster(anyString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PollRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(coordinator, times(1)).submitAll(requests.capture());
        assertEquals(3, requests.getValue().size(), "each roster player is polled once, not once per server");

        // each server still gets its own roster rows and result
        verify(clanMembers).upsert(eq(1L), eq("New"), anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        verify(clanMembers).upsert(eq(2L), eq("New"), anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        assertEquals(1, results.get(1L).newMembers());
        assertEquals(1, results.get(2L).newMembers());
        assertEquals(3, results.get(2L).rosterSize());
    }

    @Test
    void aSingleServerStillSyncsLikeBefore() {
        var result = service.syncAndPoll(one);

        verify(apiClient, times(1)).fetchClanRoster("Younglings");
        assertEquals(3, result.rosterSize());
        assertEquals(1, result.newMembers());
    }

    @Test
    void aServerWithNoClanIsSkipped() {
        Guild none = guild(3L, null);
        when(settings.getEffective(3L)).thenReturn(new GuildSettings(3L, null, null, null, null, null, null, null, null, true, null, null, null));

        assertEquals(0, service.syncAndPoll(none).rosterSize());
        verify(apiClient, never()).fetchClanRoster(anyString());
    }

    @Test
    void clansAreGroupedIgnoringCaseAndSpacing() {
        assertEquals(ClanSyncService.clanKey("Eternal  Rising"), ClanSyncService.clanKey(" eternal_rising "));

        Guild other = guild(9L, "Someone Else");
        var groups = ClanSyncScheduler.groupByClan(List.of(one, two, other, guild(10L, null)), id -> settings.getEffective(id).clanName());

        assertEquals(2, groups.size());
        assertEquals(List.of(one, two), groups.get("younglings"));
        assertTrue(groups.containsKey("someone else"));
    }

    @Test
    void oneServerFailingDoesNotStopTheOtherOrTheNextClan() {
        // a failure while rewriting server 1's roster is logged and server 2 still syncs
        when(clanMembers.getAll(1L, true)).thenThrow(new RuntimeException("db down"));

        Map<Long, ClanSyncService.SyncResult> results = service.syncAndPollGroup(List.of(one, two));

        assertTrue(results.containsKey(2L));
        assertTrue(!results.containsKey(1L));
    }
}
