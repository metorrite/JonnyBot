package com.younglings.bot.plugin;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.plugin.PluginPointsRules.XpRule;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsRules.Category;
import com.younglings.bot.points.PointsService;
import com.younglings.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
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
import java.util.concurrent.CompletableFuture;

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

    @Test
    void theGraceIsASettingHeldToItsBounds() {
        assertEquals(PluginXpPoints.GRACE, PluginXpPoints.grace(0), "unset keeps 15 minutes");
        assertEquals(java.time.Duration.ofMinutes(60), PluginXpPoints.grace(60));
        assertEquals(PluginXpPoints.MAX_GRACE, PluginXpPoints.grace(100_000));

        BotConfig config = mock(BotConfig.class);
        when(config.getPluginXpPointsEnabled()).thenReturn(true);
        when(config.getPluginXpPointsGraceMinutes()).thenReturn(60L);
        when(repository.openXpDays(any(), any())).thenReturn(List.of());
        new PluginXpPoints(repository, points, service, hub, config).withClock(Clock.fixed(NOW, ZoneOffset.UTC)).sweep();
        // 00:20 UTC with an hour's grace: the 25th waits
        verify(repository).openXpDays(DAY.plusDays(1).minusDays(PluginXpPoints.RETRY_DAYS), DAY);
    }

    // ---------- members found through the hub (T-712) ----------

    private final JDA jda = mock(JDA.class);
    private final Guild guild = mock(Guild.class);

    private static RuneClansHubClient.Answer gainers(String alias, Long discordId, long xp) {
        DataObject row = DataObject.empty().put("alias", alias).put("xp", xp);
        if (discordId != null) row.put("discordId", Long.toString(discordId));
        return new RuneClansHubClient.Answer(200, DataObject.empty().put("day", DAY.toString()).put("players", DataArray.empty().add(row)).put("truncated", false));
    }

    private static PluginPointsRepository.XpRuleOn onSince(long guildId, LocalDate since) {
        return new PluginPointsRepository.XpRuleOn(guildId, since);
    }

    /** The bot is in the server; these members are in its cache (online). */
    private PluginXpPoints inServer(Long... online) {
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        when(guild.getIdLong()).thenReturn(GUILD);
        for (long user : online) when(guild.getMemberById(user)).thenReturn(mock(Member.class));
        return xpPoints.withJda(jda);
    }

    /** Discord's answer when the cache doesn't hold the member. */
    @SuppressWarnings("unchecked")
    private void lookedUp(long user, CompletableFuture<Member> answer) {
        CacheRestAction<Member> action = mock(CacheRestAction.class);
        when(action.submit()).thenReturn(answer);
        when(guild.retrieveMemberById(user)).thenReturn(action);
    }

    private static CompletableFuture<Member> discordSays(ErrorResponse error) {
        ErrorResponseException e = mock(ErrorResponseException.class);
        when(e.getErrorResponse()).thenReturn(error);
        return CompletableFuture.failedFuture(e);
    }

    @Test
    void aMemberWhoseXpReachesOnlyTheHubIsPaidForTheDay() {
        serverUsesPoints(true);
        paysDone();
        inServer(BOB);
        when(repository.xpRuleGuilds()).thenReturn(List.of(onSince(GUILD, DAY.minusDays(30))));
        when(hub.xpGainers(GUILD, DAY)).thenReturn(gainers("a1_bob", BOB, 250_000));
        xpPoints.noteHubGainers(DAY, DAY.plusDays(1), NOW);
        verify(repository).noteXpDay(GUILD, BOB, DAY);
        verify(repository).markHubAsked(GUILD, DAY, NOW);

        // the usual settling: Bob's alias came with the answer, so the hub isn't asked for it again
        when(repository.openXpMembers(GUILD, DAY)).thenReturn(List.of(BOB));
        when(repository.xpRule(GUILD)).thenReturn(TEN_PER_100K_CAP_50);
        when(hub.xpGains(eq(GUILD), eq(List.of("a1_bob")), isNull(), eq(FROM), eq(TO))).thenReturn(gains("a1_bob", "250000"));
        when(repository.settleXpDay(anyLong(), anyLong(), any(), any(), anyInt(), any())).thenReturn(true);
        assertEquals(1, xpPoints.settle(GUILD, DAY, NOW));
        verify(service).apply(GUILD, BOB, 20, "RuneSync: 250k XP gained", Category.PLUGIN, "plugin:XP:" + BOB + ":2026-10-25", null);
        verify(hub, never()).aliasIfAnswered(BOB);

        // a day marked asked (kept in the database, so a restart too) isn't asked again
        when(repository.hubAskedDays(DAY)).thenReturn(Set.of(new PluginPointsRepository.XpDay(GUILD, DAY)));
        xpPoints.noteHubGainers(DAY, DAY.plusDays(1), NOW);
        verify(hub, times(1)).xpGainers(GUILD, DAY);
    }

    @Test
    void daysBeforeTheRuleWasSwitchedOnArentAskedAbout() {
        inServer(BOB);
        // switched on on the 25th: the days before passed with the rule off (or before it existed), so nobody is paid for them
        when(repository.xpRuleGuilds()).thenReturn(List.of(onSince(GUILD, DAY)));
        when(hub.xpGainers(eq(GUILD), any())).thenReturn(gainers("a1_bob", BOB, 250_000));
        xpPoints.noteHubGainers(DAY.minusDays(PluginXpPoints.RETRY_DAYS), DAY.plusDays(1), NOW);
        verify(hub, times(1)).xpGainers(eq(GUILD), any());
        verify(hub).xpGainers(GUILD, DAY);
        verify(repository, never()).noteXpDay(eq(GUILD), anyLong(), eq(DAY.minusDays(2)));
        verify(repository).noteXpDay(GUILD, BOB, DAY);
    }

    @Test
    void aMemberLookedUpOnceIsntLookedUpAgainWhenTheDayIsRetried() {
        inServer();
        long carol = Long.parseLong("3".repeat(18));
        lookedUp(BOB, CompletableFuture.completedFuture(mock(Member.class)));
        lookedUp(carol, discordSays(ErrorResponse.SERVER_ERROR));
        DataArray rows = DataArray.empty()
                .add(DataObject.empty().put("alias", "a1_bob").put("discordId", Long.toString(BOB)).put("xp", 300_000L))
                .add(DataObject.empty().put("alias", "a1_carol").put("discordId", Long.toString(carol)).put("xp", 200_000L));
        when(repository.xpRuleGuilds()).thenReturn(List.of(onSince(GUILD, DAY.minusDays(30))));
        when(hub.xpGainers(GUILD, DAY)).thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("players", rows)));
        xpPoints.noteHubGainers(DAY, DAY.plusDays(1), NOW);
        xpPoints.noteHubGainers(DAY, DAY.plusDays(1), NOW);

        verify(guild, times(1)).retrieveMemberById(BOB); // the first pass's answer is kept
        verify(guild, times(2)).retrieveMemberById(carol); // Discord couldn't say: asked again
        verify(repository, never()).markHubAsked(anyLong(), any(), any());
    }

    @Test
    void aMemberOfflineOnDiscordIsLookedUpAndOneWhoLeftIsSkipped() {
        inServer();
        lookedUp(BOB, CompletableFuture.completedFuture(mock(Member.class)));
        lookedUp(ALICE, discordSays(ErrorResponse.UNKNOWN_MEMBER));
        DataArray rows = DataArray.empty()
                .add(DataObject.empty().put("alias", "a1_bob").put("discordId", Long.toString(BOB)).put("xp", 300_000L))
                .add(DataObject.empty().put("alias", "a1_alice").put("discordId", Long.toString(ALICE)).put("xp", 200_000L));
        when(repository.xpRuleGuilds()).thenReturn(List.of(onSince(GUILD, DAY.minusDays(30))));
        when(hub.xpGainers(GUILD, DAY)).thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("players", rows)));
        xpPoints.noteHubGainers(DAY, DAY.plusDays(1), NOW);

        verify(repository).noteXpDay(GUILD, BOB, DAY); // offline, so not in the cache, but still in the server
        verify(repository, never()).noteXpDay(GUILD, ALICE, DAY); // left the server
        verify(repository).markHubAsked(GUILD, DAY, NOW);
    }

    @Test
    void aMemberDiscordCantAnswerForLeavesTheDayForTheNextPass() {
        inServer();
        lookedUp(BOB, discordSays(ErrorResponse.SERVER_ERROR));
        when(repository.xpRuleGuilds()).thenReturn(List.of(onSince(GUILD, DAY.minusDays(30))));
        when(hub.xpGainers(GUILD, DAY)).thenReturn(gainers("a1_bob", BOB, 250_000));
        xpPoints.noteHubGainers(DAY, DAY.plusDays(1), NOW);

        verify(repository, never()).noteXpDay(anyLong(), anyLong(), any());
        verify(repository, never()).markHubAsked(anyLong(), any(), any());
        assertNull(PluginXpPoints.isMember(guild, BOB));
    }

    @Test
    void aServerTheBotIsntInIsntAskedAbout() {
        when(repository.xpRuleGuilds()).thenReturn(List.of(onSince(GUILD, DAY.minusDays(30))));
        xpPoints.noteHubGainers(DAY, DAY.plusDays(1), NOW); // JDA not ready
        xpPoints.withJda(jda).noteHubGainers(DAY, DAY.plusDays(1), NOW); // the bot left the server
        verify(hub, never()).xpGainers(anyLong(), any());
        verify(repository, never()).markHubAsked(anyLong(), any(), any());
    }

    @Test
    void anOlderHubIsAskedAgainLaterAndADownHubEndsThePass() {
        long other = Long.parseLong("8".repeat(18));
        Guild otherGuild = mock(Guild.class);
        inServer();
        when(jda.getGuildById(other)).thenReturn(otherGuild);
        LocalDate before = DAY.minusDays(1);
        when(repository.xpRuleGuilds()).thenReturn(List.of(onSince(GUILD, DAY.minusDays(30)), onSince(other, DAY.minusDays(30))));
        when(hub.xpGainers(GUILD, before)).thenReturn(new RuneClansHubClient.Answer(404, null));
        when(hub.xpGainers(other, before)).thenReturn(null);
        xpPoints.noteHubGainers(before, DAY.plusDays(1), NOW);
        xpPoints.noteHubGainers(before, DAY.plusDays(1), NOW);

        // a 404 isn't remembered and ends that server's asking for the pass; no answer ends the whole pass
        verify(hub, times(2)).xpGainers(GUILD, before);
        verify(hub, never()).xpGainers(GUILD, DAY);
        verify(hub, times(2)).xpGainers(other, before);
        verify(hub, never()).xpGainers(other, DAY);
        verify(repository, never()).noteXpDay(anyLong(), anyLong(), any());
        verify(repository, never()).markHubAsked(anyLong(), any(), any());
    }

    @Test
    void aGainerWhoseGrantHidesTheirDiscordIdCantBePaid() {
        inServer();
        when(repository.xpRuleGuilds()).thenReturn(List.of(onSince(GUILD, DAY.minusDays(30))));
        when(hub.xpGainers(GUILD, DAY)).thenReturn(gainers("a1_alice", null, 900_000));
        xpPoints.noteHubGainers(DAY, DAY.plusDays(1), NOW);
        verify(repository, never()).noteXpDay(anyLong(), anyLong(), any());
        verify(repository).markHubAsked(GUILD, DAY, NOW);
    }

    @Test
    void theSweepAsksTheHubBeforeSettling() {
        inServer();
        when(repository.xpRuleGuilds()).thenReturn(List.of(onSince(GUILD, DAY.minusDays(30))));
        when(repository.openXpDays(any(), any())).thenReturn(List.of());
        when(hub.xpGainers(eq(GUILD), any())).thenReturn(gainers("a1_bob", BOB, 1));
        when(guild.getMemberById(BOB)).thenReturn(mock(Member.class));
        xpPoints.sweep();
        // the settle-able days only: the last RETRY_DAYS days up to the 25th
        verify(hub).xpGainers(GUILD, DAY);
        verify(hub, never()).xpGainers(GUILD, DAY.plusDays(1));
        verify(hub, times(PluginXpPoints.RETRY_DAYS)).xpGainers(eq(GUILD), any());
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
