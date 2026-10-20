package com.younglings.bot.internal;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.notice.BotOwners;
import com.younglings.bot.premium.PremiumGuildRepository;
import com.younglings.bot.runescape.ClanMemberRepository;
import com.younglings.bot.runescape.polling.PollCoordinator;
import com.younglings.bot.runescape.polling.PollEngine;
import com.younglings.bot.runescape.polling.PollPriority;
import com.younglings.bot.runescape.polling.PollTally;
import com.younglings.bot.runescape.polling.RuneMetricsBudget;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The owner's budget page: per-tier counters and lag, per-server demand with a shared clan counted once, no player names. */
class RuneMetricsBudgetAdminApiTest {
    private PollCoordinator coordinator;
    private RuneMetricsBudget budget;
    private ClanMemberRepository members;
    private GuildSettingsService settings;
    private PremiumGuildRepository premium;
    private BotOwners owners;
    private RuneMetricsBudgetAdminApi api;
    private Guild home;
    private Member actor;
    private final PollTally tally = new PollTally();

    @BeforeEach
    void setUp() {
        coordinator = mock(PollCoordinator.class);
        budget = mock(RuneMetricsBudget.class);
        members = mock(ClanMemberRepository.class);
        settings = mock(GuildSettingsService.class);
        premium = mock(PremiumGuildRepository.class);
        owners = mock(BotOwners.class);
        BotConfig config = mock(BotConfig.class);
        when(config.getRunescapeClanPollWindowMinutes()).thenReturn(180L);
        when(config.getRunescapePremiumPollWindowMinutes()).thenReturn(60L);
        when(config.getRunescapeLinkedPollWindowMinutes()).thenReturn(480L);

        PollEngine.Status engine = new PollEngine.Status(3, new java.util.EnumMap<>(Map.of(PollPriority.CLAN, 3)), null, 5, 10, 2_000, 1, 100, 20, 0, 4, 1, 2, 12, 100, 0);
        PollCoordinator.Status status = new PollCoordinator.Status(engine, List.of());
        when(coordinator.status()).thenReturn(status);
        when(coordinator.tally()).thenReturn(tally);
        when(coordinator.oldestWaitingByPriority()).thenReturn(Map.of(PollPriority.CLAN, 7_000_000L));
        when(budget.history(14)).thenReturn(List.of());
        when(owners.isOwner(any(), anyLong())).thenReturn(true);

        api = new RuneMetricsBudgetAdminApi(coordinator, budget, members, settings, premium, config, owners);

        JDA jda = mock(JDA.class);
        home = server(jda, 1L, "Younglings", "Younglings", 60, false);
        Guild b = server(jda, 2L, "Younglings friends", " younglings ", 60, false);
        Guild c = server(jda, 3L, "Other", "Other Clan", 30, true);
        when(jda.getGuilds()).thenReturn(List.of(home, b, c));
        when(home.getJDA()).thenReturn(jda);
        actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(7L);
    }

    private Guild server(JDA jda, long id, String name, String clan, int players, boolean isPremium) {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(id);
        when(guild.getName()).thenReturn(name);
        when(guild.getJDA()).thenReturn(jda);
        when(settings.getEffective(id)).thenReturn(new GuildSettings(id, clan, null, null, null, null, null, null, null, true, clan, null, null));
        List<ClanMemberRepository.ClanMemberRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < players; i++) rows.add(new ClanMemberRepository.ClanMemberRow(i, id, "P" + i, "Recruit", null, null, true, 0, 0, null));
        when(members.getAll(id, true)).thenReturn(rows);
        when(premium.isPremium(id)).thenReturn(isPremium);
        return guild;
    }

    @Test
    void onlyTheOwnerMaySeeIt() {
        when(owners.isOwner(any(), anyLong())).thenReturn(false);

        assertEquals(403, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(home, actor, "GET", new String[]{"runemetrics-budget"}, () -> null)).status);
        assertEquals(405, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(home, actor, "POST", new String[]{"runemetrics-budget"}, () -> null)).status);
    }

    @Test
    void eachTierShowsItsCountersAndHowFarBehindItIs() {
        tally.record(PollPriority.CLAN, PollTally.Kind.SENT);
        tally.record(PollPriority.CLAN, PollTally.Kind.RATE_LIMITED);

        DataObject o = api.route(home, actor, "GET", new String[]{"runemetrics-budget"}, () -> null);

        DataObject clan = null;
        for (int i = 0; i < o.getArray("tiers").length(); i++) if (o.getArray("tiers").getObject(i).getString("tier").equals("CLAN")) clan = o.getArray("tiers").getObject(i);
        assertEquals(1, clan.getInt("sent"));
        assertEquals(1, clan.getInt("rateLimited"));
        assertEquals(180, clan.getInt("windowMinutes"));
        assertEquals(7000, clan.getInt("lagSeconds"));
        assertTrue(clan.getBoolean("behind"), "waiting for 7000 s of a 180 minute window is behind");
        assertEquals(4, o.getArray("tiers").length());
    }

    @Test
    void aClanInTwoServersCountsOnceInTheTotalButShowsOnBoth() {
        DataObject o = api.overview(home);

        assertEquals(3, o.getArray("servers").length());
        // 60 players at 180 minutes = 480 a day; the same clan in two servers counts once; the premium clan of 30 at 60 minutes = 720
        assertEquals(480 + 720, o.getLong("clanDemandPerDay"));
        long shared = 0;
        for (int i = 0; i < o.getArray("servers").length(); i++) {
            DataObject s = o.getArray("servers").getObject(i);
            if (s.getInt("players") == 60) {
                assertEquals(1, s.getInt("sharedWith"));
                assertEquals(480, s.getLong("requestsPerDay"));
                shared++;
            } else {
                assertEquals("PREMIUM_CLAN", s.getString("tier"));
                assertEquals(720, s.getLong("requestsPerDay"));
            }
        }
        assertEquals(2, shared);
    }

    @Test
    void theCapacityIsWhatThePaceAllowsInADay() {
        assertEquals(43_200, api.overview(home).getLong("capacityPerDay"), "one request every 2 seconds");
    }

    @Test
    void noPlayerNameIsEverInTheAnswer() {
        assertFalse(api.overview(home).toString().contains("\"P1\""));
    }

    @Test
    void aServerWithoutAClanIsLeftOut() {
        when(settings.getEffective(3L)).thenReturn(new GuildSettings(3L, null, null, null, null, null, null, null, null, true, null, null, null));

        assertEquals(2, api.overview(home).getArray("servers").length());
    }

    @Test
    void demandIsPlayersTimesRequestsPerDayOfItsTier() {
        assertEquals(480.0, RuneMetricsBudgetAdminApi.demandPerDay(new RuneMetricsBudgetAdminApi.ServerLoad(1, "a", "a", "a", false, 60), 180, 60), 0.001);
        assertEquals(1440.0, RuneMetricsBudgetAdminApi.demandPerDay(new RuneMetricsBudgetAdminApi.ServerLoad(1, "a", "a", "a", true, 60), 180, 60), 0.001);
        assertEquals(0.0, RuneMetricsBudgetAdminApi.demandPerDay(new RuneMetricsBudgetAdminApi.ServerLoad(1, "a", "a", "a", false, 60), 0, 60), 0.001);
    }
}
