package com.younglings.bot.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.clanprofile.ClanProfileService;
import com.younglings.bot.runescape.PlayerLinkService;
import com.younglings.bot.runescape.ClanSyncService;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hub's clan list costs a fixed few reads per clan (T-180): no profile is fetched again after the list loaded it, and members are counted
 * by the database instead of loading every roster row.
 */
class DirectoryListQueriesTest {
    private static final int CLANS = 12;

    private static ClanProfile clan(long guildId) {
        return new ClanProfile(guildId, ClanProfile.Visibility.PUBLIC, "clan-" + guildId, "Clan " + guildId, null, null, null, null, null, Set.of(),
                ClanProfile.Recruiting.CLOSED, null, null, true, "Clan " + guildId, java.time.Instant.EPOCH, 1L, null, null, null);
    }

    private static int call(DirectoryApi api, String path) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        int[] status = {-1};
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost" + path));
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        doAnswer(inv -> {
            status[0] = inv.getArgument(0);
            return null;
        }).when(exchange).sendResponseHeaders(any(Integer.class), any(Long.class));
        api.handle(exchange, mock(JDA.class));
        return status[0];
    }

    @Test
    void theClanListDoesNotFetchEachProfileAgainOrLoadRosters() throws Exception {
        ClanProfileRepository repository = mock(ClanProfileRepository.class);
        ClanSyncService clanSync = mock(ClanSyncService.class);
        List<ClanProfile> profiles = new ArrayList<>();
        for (long g = 1; g <= CLANS; g++) profiles.add(clan(g));
        when(repository.shared()).thenReturn(profiles);
        when(clanSync.getClanName(anyLong())).thenAnswer(inv -> "Clan " + inv.getArgument(0));
        when(clanSync.countRoster(anyLong(), anyBoolean())).thenReturn(40);
        ClanProfileService service = new ClanProfileService(repository, clanSync, mock(PlayerLinkService.class));
        DirectoryApi api = new DirectoryApi(repository, service, mock(SiteStatsRepository.class), List.of());

        assertEquals(200, call(api, "/internal/directory/clans"));

        verify(repository, never()).get(anyLong());                       // no profile is fetched again
        verify(clanSync, never()).getRoster(anyLong(), anyBoolean());     // members are counted, not loaded
        verify(clanSync, times(CLANS)).countRoster(anyLong(), eq(true));
        verify(clanSync, times(CLANS)).getClanName(anyLong());            // the clan name once per clan
    }

    @Test
    void theLeaderboardDoesNotLookEachClanUpAgainBySlug() throws Exception {
        ClanProfileRepository repository = mock(ClanProfileRepository.class);
        ClanSyncService clanSync = mock(ClanSyncService.class);
        List<ClanProfile> profiles = new ArrayList<>();
        for (long g = 1; g <= CLANS; g++) profiles.add(clan(g));
        when(repository.shared()).thenReturn(profiles);
        when(clanSync.getClanName(anyLong())).thenAnswer(inv -> "Clan " + inv.getArgument(0));
        SiteStatsRepository stats = mock(SiteStatsRepository.class);
        when(stats.xpGains(anyLong(), any())).thenReturn(List.of());
        ClanProfileService service = new ClanProfileService(repository, clanSync, mock(PlayerLinkService.class));
        DirectoryApi api = new DirectoryApi(repository, service, stats, List.of());

        assertEquals(200, call(api, "/internal/directory/leaderboards"));

        verify(repository, never()).get(anyLong());
        verify(repository, never()).bySlug(anyString());
    }

    @Test
    void aMemberCountIsADatabaseCount() {
        ClanSyncService clanSync = mock(ClanSyncService.class);
        when(clanSync.countRoster(7L, true)).thenReturn(123);
        ClanProfileService service = new ClanProfileService(mock(ClanProfileRepository.class), clanSync, mock(PlayerLinkService.class));
        assertEquals(123, service.memberCount(7L));
        verify(clanSync, never()).getRoster(anyLong(), anyBoolean());
    }
}
