package com.younglings.bot.plugin;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.plugin.PluginPointsRules.XpRule;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsRules.Category;
import com.younglings.bot.points.PointsService;
import com.younglings.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Points for XP gained (T-542): N points per M XP per UTC day, a daily cap, one award per member per day from the hub's ledger, off by default. */
class PluginXpPointsTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long ALICE = Long.parseLong("1".repeat(18));
    private static final long BOB = Long.parseLong("2".repeat(18));
    private static final LocalDate DAY = LocalDate.parse("2026-10-25");
    private static final Instant NOW = Instant.parse("2026-10-26T00:20:00Z");
    private static final Instant FROM = Instant.parse("2026-10-25T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-26T00:00:00Z");
    private static final XpRule TEN_PER_100K_CAP_50 = new XpRule(true, 10, 100_000, 50);

    private final PluginPointsRepository repository = mock(PluginPointsRepository.class);
    private final PointsRepository points = mock(PointsRepository.class);
    private final PointsService service = mock(PointsService.class);
    private final RuneClansHubClient hub = mock(RuneClansHubClient.class);
    private final PluginXpPoints xpPoints = newXpPoints(true);

    private PluginXpPoints newXpPoints(boolean switchedOn) {
        BotConfig config = mock(BotConfig.class);
        when(config.getPluginXpPointsEnabled()).thenReturn(switchedOn);
        when(hub.enabled()).thenReturn(true);
        return new PluginXpPoints(repository, points, service, hub, config).withClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void serverUsesPoints(boolean on) {
        when(points.settings(GUILD)).thenReturn(new PointsRepository.Settings(GUILD, on, "points", null));
    }

    private static RuneClansHubClient.Answer gains(String... aliasAndXp) {
        DataArray players = DataArray.empty();
        for (int i = 0; i < aliasAndXp.length; i += 2) players.add(DataObject.empty().put("alias", aliasAndXp[i]).put("xp", Long.parseLong(aliasAndXp[i + 1])));
        return new RuneClansHubClient.Answer(200, DataObject.empty().put("players", players).put("notCounted", DataArray.empty()));
    }

    private void paysDone() {
        when(service.apply(anyLong(), anyLong(), anyLong(), anyString(), any(), anyString(), any())).thenReturn(new PointsService.Result(PointsService.Outcome.DONE, null, 0, 1));
    }

    // ---------- the rule ----------

    @Test
    void theRulePaysWholeStepsHeldToTheCapAndNothingWhileOff() {
        assertEquals(0, PluginPointsRules.xpPoints(XpRule.OFF, 5_000_000), "off by default");
        assertEquals(0, PluginPointsRules.xpPoints(new XpRule(false, 10, 100_000, 0), 5_000_000), "switched off keeps its numbers but pays nothing");
        assertEquals(0, PluginPointsRules.xpPoints(TEN_PER_100K_CAP_50, 99_999), "less than one step");
        assertEquals(10, PluginPointsRules.xpPoints(TEN_PER_100K_CAP_50, 199_999));
        assertEquals(40, PluginPointsRules.xpPoints(TEN_PER_100K_CAP_50, 400_000));
        assertEquals(50, PluginPointsRules.xpPoints(TEN_PER_100K_CAP_50, 12_000_000), "the cap holds");
        assertEquals(1_200, PluginPointsRules.xpPoints(new XpRule(true, 10, 100_000, 0), 12_000_000), "0 = no cap");
        assertEquals(PluginPointsRules.MAX_DAILY_CAP, PluginPointsRules.xpPoints(new XpRule(true, PluginPointsRules.MAX_POINTS, PluginPointsRules.MIN_PER_XP, 0), 5_000_000_000L),
                "never more than one ledger entry may hold");
    }

    @Test
    void theReasonSaysHowMuchXpRoundedDown() {
        assertEquals("RuneSync: 1.2M XP gained", PluginPointsRules.xpReason(1_299_999));
        assertEquals("RuneSync: 12M XP gained", PluginPointsRules.xpReason(12_000_000));
        assertEquals("RuneSync: 12.3k XP gained", PluginPointsRules.xpReason(12_345));
        assertEquals("RuneSync: 9,500 XP gained", PluginPointsRules.xpReason(9_500));
        assertEquals("RuneSync: 1,234.5M XP gained", PluginPointsRules.xpReason(1_234_567_890));
    }

    @Test
    void theRuleIsChecked() {
        assertNull(PluginPointsRules.xpProblem(TEN_PER_100K_CAP_50));
        assertNull(PluginPointsRules.xpProblem(XpRule.OFF));
        assertNotNull(PluginPointsRules.xpProblem(new XpRule(true, 0, 100_000, 0)), "on with no points");
        assertNotNull(PluginPointsRules.xpProblem(new XpRule(true, 10, PluginPointsRules.MIN_PER_XP - 1, 0)));
        assertNotNull(PluginPointsRules.xpProblem(new XpRule(true, 10, PluginPointsRules.MAX_PER_XP + 1, 0)));
        assertNotNull(PluginPointsRules.xpProblem(new XpRule(true, PluginPointsRules.MAX_POINTS + 1, 100_000, 0)));
        assertNotNull(PluginPointsRules.xpProblem(new XpRule(true, 10, 100_000, -1)));
        assertNotNull(PluginPointsRules.problem(new PluginPointsRules.Rule(PluginEventType.XP, 5, 0, null)), "XP still has no per-report rule");
    }

    // ---------- settling a day ----------

    @Test
    void aDayPaysEachMemberOnceByTheRuleWithAKeyPerDay() {
        serverUsesPoints(true);
        paysDone();
        when(repository.openXpMembers(GUILD, DAY)).thenReturn(List.of(ALICE, BOB));
        when(repository.xpRule(GUILD)).thenReturn(TEN_PER_100K_CAP_50);
        when(hub.aliasIfAnswered(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.aliasIfAnswered(BOB)).thenReturn(Optional.of("a1_bob"));
        when(hub.xpGains(eq(GUILD), eq(List.of("a1_alice", "a1_bob")), isNull(), eq(FROM), eq(TO))).thenReturn(gains("a1_alice", "1250000", "a1_bob", "250000"));
        when(repository.settleXpDay(anyLong(), anyLong(), any(), any(), anyInt(), any())).thenReturn(true);

        assertEquals(2, xpPoints.settle(GUILD, DAY, NOW));

        verify(service).apply(GUILD, ALICE, 50, "RuneSync: 1.2M XP gained", Category.PLUGIN, "plugin:XP:" + ALICE + ":2026-10-25", null);
        verify(service).apply(GUILD, BOB, 20, "RuneSync: 250k XP gained", Category.PLUGIN, "plugin:XP:" + BOB + ":2026-10-25", null);
        verify(repository).settleXpDay(GUILD, ALICE, DAY, 1_250_000L, 50, NOW);
        verify(repository).settleXpDay(GUILD, BOB, DAY, 250_000L, 20, NOW);
    }

    @Test
    void nothingIsPaidUnlessTheRuleIsOnAndTheServerUsesPoints() {
        when(repository.openXpMembers(GUILD, DAY)).thenReturn(List.of(ALICE));
        serverUsesPoints(true);
        when(repository.xpRule(GUILD)).thenReturn(new XpRule(false, 10, 100_000, 0));
        assertEquals(0, xpPoints.settle(GUILD, DAY, NOW));

        serverUsesPoints(false);
        when(repository.xpRule(GUILD)).thenReturn(TEN_PER_100K_CAP_50);
        assertEquals(0, xpPoints.settle(GUILD, DAY, NOW));

        verify(service, never()).apply(anyLong(), anyLong(), anyLong(), anyString(), any(), anyString(), any());
        verify(hub, never()).xpGains(anyLong(), any(), any(), any(), any());
        verify(repository, times(2)).settleXpDay(GUILD, ALICE, DAY, null, 0, NOW);
    }

    @Test
    void aHubThatCantAnswerLeavesTheDayForTheNextPass() {
        serverUsesPoints(true);
        when(repository.openXpMembers(GUILD, DAY)).thenReturn(List.of(ALICE, BOB));
        when(repository.xpRule(GUILD)).thenReturn(TEN_PER_100K_CAP_50);
        when(hub.aliasIfAnswered(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.aliasIfAnswered(BOB)).thenReturn(null); // couldn't be asked
        when(hub.xpGains(anyLong(), any(), any(), any(), any())).thenReturn(null);

        assertEquals(0, xpPoints.settle(GUILD, DAY, NOW));

        verify(service, never()).apply(anyLong(), anyLong(), anyLong(), anyString(), any(), anyString(), any());
        verify(repository, never()).settleXpDay(anyLong(), anyLong(), any(), any(), anyInt(), any());
    }

    @Test
    void aMemberTheHubCantCountIsSettledWithNothing() {
        serverUsesPoints(true);
        when(repository.openXpMembers(GUILD, DAY)).thenReturn(List.of(ALICE, BOB));
        when(repository.xpRule(GUILD)).thenReturn(TEN_PER_100K_CAP_50);
        when(hub.aliasIfAnswered(ALICE)).thenReturn(Optional.of("a1_alice"));
        when(hub.aliasIfAnswered(BOB)).thenReturn(Optional.empty()); // no identity grant to the bot
        when(hub.xpGains(anyLong(), any(), any(), any(), any())).thenReturn(gains()); // alice shares no XP in this server

        assertEquals(0, xpPoints.settle(GUILD, DAY, NOW));

        verify(repository).settleXpDay(GUILD, ALICE, DAY, null, 0, NOW);
        verify(repository).settleXpDay(GUILD, BOB, DAY, null, 0, NOW);
        verify(service, never()).apply(anyLong(), anyLong(), anyLong(), anyString(), any(), anyString(), any());
    }

    @Test
    void theSweepSettlesOnlyDaysThatEndedAWhileAgo() {
        when(repository.openXpDays(any(), any())).thenReturn(List.of());
        xpPoints.sweep();
        // 00:20 UTC on the 26th: the 25th ended 20 minutes ago, past the 15-minute grace
        verify(repository).openXpDays(DAY.plusDays(1).minusDays(PluginXpPoints.RETRY_DAYS), DAY.plusDays(1));

        PluginXpPoints early = newXpPoints(true).withClock(Clock.fixed(Instant.parse("2026-10-26T00:10:00Z"), ZoneOffset.UTC));
        early.sweep();
        verify(repository).openXpDays(DAY.plusDays(1).minusDays(PluginXpPoints.RETRY_DAYS), DAY);
    }

    // ---------- noting who sent XP ----------

    @Test
    void membersAreNotedInServersWhoseRuleIsOnAtMostOnceAnHour() {
        long other = Long.parseLong("8".repeat(18));
        when(repository.xpRule(GUILD)).thenReturn(TEN_PER_100K_CAP_50);
        when(repository.xpRule(other)).thenReturn(XpRule.OFF);
        LocalDate today = LocalDate.ofInstant(NOW, ZoneOffset.UTC);

        xpPoints.note(ALICE, List.of(GUILD, other), Set.of(today), NOW);
        xpPoints.note(ALICE, List.of(GUILD, other), Set.of(today), NOW.plusSeconds(60));
        verify(repository, times(1)).noteXpDay(GUILD, ALICE, today);
        verify(repository, never()).noteXpDay(eq(other), anyLong(), any());

        xpPoints.note(ALICE, List.of(GUILD, other), Set.of(today), NOW.plus(PluginXpPoints.NOTED_KEPT).plusSeconds(1));
        verify(repository, times(2)).noteXpDay(GUILD, ALICE, today);
    }

    @Test
    void onlyXpReportsOfTodayAndYesterdayCount() {
        List<PluginPayload.Event> events = List.of(
                new PluginPayload.Event("x0", PluginEventType.XP, NOW, null, null, null, "{}"),
                new PluginPayload.Event("x1", PluginEventType.XP, NOW.minusSeconds(3_600), null, null, null, "{}"),
                new PluginPayload.Event("x2", PluginEventType.XP, NOW.minusSeconds(86_400 * 3), null, null, null, "{}"),
                new PluginPayload.Event("k1", PluginEventType.KILL, NOW, null, null, null, "{}"));
        assertEquals(Set.of(DAY.plusDays(1), DAY), PluginXpPoints.xpDays(events, NOW));
        assertTrue(PluginXpPoints.xpDays(events.subList(2, 4), NOW).isEmpty(), "too old, or not XP");
    }

    @Test
    void offeredOnlyWithTheSwitchAndTheHub() {
        assertTrue(xpPoints.offered());
        assertFalse(newXpPoints(false).offered(), "off by default");
        when(hub.enabled()).thenReturn(false);
        assertFalse(xpPoints.offered());
    }
}
