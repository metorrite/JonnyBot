package com.runeclans.bot.rsnlink.verify;

import com.runeclans.bot.rsnlink.verify.PublicData.Board;
import com.runeclans.bot.rsnlink.verify.VerifyFakes.FakePublicData;
import com.runeclans.bot.rsnlink.verify.VerifyFakes.MutableClock;
import com.runeclans.bot.rsnlink.verify.VerifyFakes.RecordingSink;
import com.runeclans.bot.rsnlink.verify.VerifyStore.Claim;
import com.runeclans.bot.rsnlink.verify.VerifyStore.ClaimStatus;
import com.runeclans.bot.rsnlink.verify.VerifyStore.Watch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "plugin knew it first" rules (T-170) with Jagex's public data stubbed: no test here makes a network call. Skill ids are the plugin's
 * ({@code SkillType}): 7 Cooking, 8 Woodcutting, 10 Fishing, 14 Mining.
 */
class XpVerifierTest {
    private static final int COOKING = 7;
    private static final int WOODCUTTING = 8;
    private static final int FISHING = 10;
    private static final int MINING = 14;
    private static final String RSN = "Zezima";

    private MutableClock clock;
    private FakePublicData data;
    private MemoryVerifyStore store;
    private RecordingSink sink;
    private XpVerifier verifier;
    private final UUID link = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-22T12:00:00Z"));
        data = new FakePublicData();
        store = new MemoryVerifyStore();
        sink = new RecordingSink();
        verifier = new XpVerifier(VerifyFakes.on(), store, data, sink, clock);
    }

    private XpVerifier.Accepted report(UUID linkId, String state, Map<Integer, Long> xp10) {
        return verifier.report(new XpVerifier.Report(linkId, RSN, state, xp10));
    }

    private XpVerifier.Accepted report(String state, Map<Integer, Long> xp10) {
        return report(link, state, xp10);
    }

    private Map<ClaimStatus, Integer> counts() {
        return store.claimCounts(link);
    }

    private int count(ClaimStatus s) {
        return counts().getOrDefault(s, 0);
    }

    private void tick(Duration d) {
        clock.advance(d);
        verifier.runDue();
    }

    /** Public data before the session: Fishing 1,000,000 and Cooking 500,000 XP on the main Hiscores. */
    private void before() {
        data.main(Map.of(FISHING, 1_000_000L, COOKING, 500_000L));
    }

    // ---------- the real owner ----------

    @Test
    void theRealOwnerIsCorroboratedAfterLoggingOut() {
        before();
        assertEquals(2, report("game", Map.of(FISHING, 10_012_345L, COOKING, 5_005_000L)).accepted());
        tick(Duration.ofSeconds(1));
        assertEquals(2, count(ClaimStatus.PENDING), "neither value was public, so both are predictions");

        tick(Duration.ofMinutes(5));
        report("lobby", Map.of());
        tick(Duration.ofSeconds(1));
        assertTrue(sink.raised.isEmpty(), "the Hiscores haven't updated yet");

        data.main(Map.of(FISHING, 1_001_234L, COOKING, 500_500L)); // floor of the tenths the plugin saw
        tick(Duration.ofMinutes(3));
        assertEquals(2, count(ClaimStatus.MATCH));
        assertEquals(1, sink.raised.size());
        String evidence = sink.raised.getFirst().evidence();
        assertTrue(evidence.contains("\"matches\":2") && evidence.contains("Fishing") && evidence.contains("Cooking"), evidence);
        assertFalse(evidence.contains("1001234") || evidence.contains("10012345"), "no XP values in the evidence");
        assertEquals("corroborated", verifier.describe(link).get("state"));
    }

    @Test
    void reportsAfterCorroborationAreIgnoredAndNothingIsReadAgain() {
        theRealOwnerIsCorroboratedAfterLoggingOut();
        int calls = data.calls.size();
        assertEquals(0, report("game", Map.of(FISHING, 10_100_000L)).accepted());
        tick(Duration.ofHours(1));
        assertEquals(calls, data.calls.size());
        assertEquals(1, sink.raised.size(), "raised once only");
    }

    @Test
    void oneMatchIsNeverEnough() {
        before();
        report("game", Map.of(FISHING, 10_012_345L));
        tick(Duration.ofSeconds(1));
        report("lobby", Map.of());
        data.main(Map.of(FISHING, 1_001_234L, COOKING, 500_000L));
        tick(Duration.ofMinutes(3));
        assertEquals(1, count(ClaimStatus.MATCH));
        assertTrue(sink.raised.isEmpty());

        // A second session, another match: now it's enough.
        report("game", Map.of(FISHING, 10_030_000L));
        tick(Duration.ofSeconds(1));
        report("logout", Map.of());
        data.main(Map.of(FISHING, 1_003_000L, COOKING, 500_000L));
        tick(Duration.ofMinutes(3));
        assertEquals(2, count(ClaimStatus.MATCH));
        assertEquals(1, sink.raised.size());
    }

    @Test
    void theLatestValueBeforeLogoutIsTheOneThatMatchesAndOlderOnesAreSuperseded() {
        before();
        report("game", Map.of(FISHING, 10_005_000L));
        tick(Duration.ofSeconds(1));
        report("game", Map.of(FISHING, 10_012_345L));
        tick(Duration.ofSeconds(1));
        report("game", Map.of(COOKING, 5_010_000L));
        tick(Duration.ofSeconds(1));
        report("lobby", Map.of());
        data.main(Map.of(FISHING, 1_001_234L, COOKING, 501_000L));
        tick(Duration.ofMinutes(3));
        assertEquals(2, count(ClaimStatus.MATCH));
        assertEquals(1, count(ClaimStatus.SUPERSEDED), "the earlier Fishing value is neither a match nor a miss");
        assertEquals(0, count(ClaimStatus.MISS));
        assertEquals(1, sink.raised.size());
    }

    @Test
    void aNewSessionStartedBeforeTheHiscoresUpdatedStillMatchesTheOldOne() {
        before();
        report("game", Map.of(FISHING, 10_012_345L, COOKING, 5_005_000L));
        tick(Duration.ofSeconds(1));
        report("lobby", Map.of());
        tick(Duration.ofSeconds(30)); // the Hiscores are a little slow; the player is already back in
        report("game", Map.of(FISHING, 10_020_000L));
        // The read for the new report is the first to see the published values.
        data.main(Map.of(FISHING, 1_001_234L, COOKING, 500_500L));
        tick(Duration.ofSeconds(1));
        assertEquals(2, count(ClaimStatus.MATCH));
        assertEquals(0, count(ClaimStatus.MISS));
        assertEquals(1, sink.raised.size());
    }

    @Test
    void anEarlierSessionPublishingDoesNotCountAgainstALaterClaim() {
        before();
        report("game", Map.of(FISHING, 10_050_000L));
        tick(Duration.ofSeconds(1));
        // Something below the claim is published (a session this plugin didn't see). The claim's own session hasn't published yet.
        data.main(Map.of(FISHING, 1_002_000L, COOKING, 500_000L));
        tick(Duration.ofMinutes(20));
        assertEquals(0, count(ClaimStatus.MISS));
        assertEquals(1, count(ClaimStatus.PENDING));
    }

    // ---------- values that prove nothing ----------

    @Test
    void aValueAlreadyOnTheHiscoresIsRefused() {
        data.main(Map.of(FISHING, 1_001_234L));
        report("game", Map.of(FISHING, 10_012_345L)); // same whole XP as already published
        tick(Duration.ofSeconds(1));
        assertEquals(1, count(ClaimStatus.REFUSED_PUBLIC));
    }

    @Test
    void aValueAlreadyOnPublicRuneMetricsIsRefusedEvenToTheTenth() {
        before();
        data.rm(Map.of(FISHING, 10_012_340L)); // RuneMetrics shows 1,001,234.0; the claim is 1,001,234.9
        report("game", Map.of(FISHING, 10_012_349L));
        tick(Duration.ofSeconds(1));
        assertEquals(1, count(ClaimStatus.REFUSED_PUBLIC), "the whole XP was public, so a copier could have guessed the tenths");
    }

    @Test
    void aFakerCopyingAfterTheOwnerLogsOutIsRefusedEveryTime() {
        // The owner logged out; the new values are public; the faker reports them.
        data.main(Map.of(FISHING, 1_001_234L, COOKING, 500_500L));
        report("game", Map.of(FISHING, 10_012_340L, COOKING, 5_005_000L));
        tick(Duration.ofSeconds(1));
        report("lobby", Map.of());
        tick(Duration.ofMinutes(3));
        assertEquals(2, count(ClaimStatus.REFUSED_PUBLIC));
        assertTrue(sink.raised.isEmpty());
    }

    @Test
    void aFakerGuessingGetsMissesThatOutweighALuckyMatch() {
        before();
        UUID faker = UUID.randomUUID();
        // Three sessions of guesses for Fishing; the owner's real values land elsewhere, once on a guess.
        long[][] rounds = {{10_020_000L, 1_003_000L}, {10_040_000L, 1_004_000L}, {10_050_000L, 1_006_000L}};
        long hs = 1_000_000L;
        for (long[] r : rounds) {
            report(faker, "game", Map.of(FISHING, r[0]));
            tick(Duration.ofSeconds(1));
            report(faker, "lobby", Map.of());
            hs = r[1];
            data.main(Map.of(FISHING, hs, COOKING, 500_000L));
            tick(Duration.ofMinutes(3));
        }
        Map<ClaimStatus, Integer> c = store.claimCounts(faker);
        assertEquals(2, c.getOrDefault(ClaimStatus.MISS, 0));
        assertEquals(1, c.getOrDefault(ClaimStatus.MATCH, 0));
        assertTrue(sink.raised.isEmpty());
        assertFalse(verifier.corroborates(2, 5), "two lucky matches don't outweigh five misses");
        assertTrue(verifier.corroborates(2, 4), "an owner who sometimes closes the client early still gets there");
    }

    @Test
    void aSmallGainMatchesButDoesNotCount() {
        before();
        report("game", Map.of(FISHING, 10_000_500L, COOKING, 5_000_400L)); // +50 and +40 XP
        tick(Duration.ofSeconds(1));
        report("lobby", Map.of());
        data.main(Map.of(FISHING, 1_000_050L, COOKING, 500_040L));
        tick(Duration.ofMinutes(3));
        assertEquals(2, count(ClaimStatus.MATCH_SMALL));
        assertTrue(sink.raised.isEmpty());
    }

    @Test
    void cappedNonsenseAndUnknownSkillsAreIgnored() {
        Map<Integer, Long> xp = new HashMap<>();
        xp.put(FISHING, Skills.MAX_XP10);
        xp.put(COOKING, 0L);
        xp.put(29, 10_000L);
        xp.put(-1, 10_000L);
        xp.put(MINING, null);
        XpVerifier.Accepted a = report("game", xp);
        assertEquals(0, a.accepted());
        assertEquals(5, a.ignored());
        assertTrue(store.openClaims(link).isEmpty());
        assertTrue(store.watch(link).isEmpty(), "nothing to check, so nothing is scheduled");
    }

    @Test
    void reportsPerHourAreCapped() {
        before();
        int total = 0;
        for (int i = 0; i < 10; i++) {
            Map<Integer, Long> xp = new HashMap<>();
            for (int s = 0; s < Skills.COUNT; s++) xp.put(s, 20_000_000L + i);
            total += report("game", xp).accepted();
        }
        assertEquals(VerifySettings.CLAIMS_PER_LINK_HOUR, total);
    }

    // ---------- public data quirks ----------

    @Test
    void unrankedBeforeAndWithoutRuneMetricsMatchesButDoesNotCount() {
        data.main(Map.of(COOKING, 500_000L)); // Fishing is under level 15 or outside the top 2 million
        report("game", Map.of(FISHING, 10_012_345L));
        tick(Duration.ofSeconds(1));
        report("lobby", Map.of());
        data.main(Map.of(FISHING, 1_001_234L, COOKING, 500_000L));
        tick(Duration.ofMinutes(3));
        assertEquals(1, count(ClaimStatus.MATCH_SMALL), "with no public value before, the gain is unknown");
    }

    @Test
    void publicRuneMetricsGivesTheBaselineForAnUnrankedSkill() {
        data.main(Map.of(COOKING, 500_000L, WOODCUTTING, 300_000L));
        data.rm(Map.of(FISHING, 10_000_000L, COOKING, 5_000_000L, WOODCUTTING, 3_000_000L));
        report("game", Map.of(FISHING, 10_012_345L, COOKING, 5_005_000L));
        tick(Duration.ofSeconds(1));
        report("lobby", Map.of());
        data.main(Map.of(FISHING, 1_001_234L, COOKING, 500_500L, WOODCUTTING, 300_000L));
        tick(Duration.ofMinutes(3));
        assertEquals(2, count(ClaimStatus.MATCH));
        assertEquals(1, sink.raised.size());
    }

    @Test
    void ironmanTablesAreReadForSkillsTheMainTableDoesNotRank() {
        data.main(Map.of(COOKING, 500_000L));
        data.ironman(Map.of(FISHING, 1_000_000L, COOKING, 500_000L));
        report("game", Map.of(FISHING, 10_012_345L, COOKING, 5_005_000L));
        tick(Duration.ofSeconds(1));
        assertEquals(Boolean.TRUE, store.watch(link).orElseThrow().ironman());
        report("lobby", Map.of());
        data.ironman(Map.of(FISHING, 1_001_234L, COOKING, 500_500L));
        data.main(Map.of(COOKING, 500_500L));
        tick(Duration.ofMinutes(3));
        assertEquals(2, count(ClaimStatus.MATCH), "the ironman table's Fishing value completes the match");
        assertEquals(1, sink.raised.size());
    }

    @Test
    void aValueAlreadyOnTheIronmanTableIsRefused() {
        data.main(Map.of(COOKING, 500_000L));
        data.ironman(Map.of(FISHING, 1_001_234L));
        report("game", Map.of(FISHING, 10_012_345L));
        tick(Duration.ofSeconds(1));
        assertEquals(1, count(ClaimStatus.REFUSED_PUBLIC));
    }

    @Test
    void aPlayerNotOnTheIronmanTableIsNotAskedAgainForAWeek() {
        data.main(Map.of(COOKING, 500_000L));
        report("game", Map.of(FISHING, 10_012_345L));
        tick(Duration.ofSeconds(1));
        assertEquals(Boolean.FALSE, store.watch(link).orElseThrow().ironman());
        assertEquals(1, data.count("IRONMAN"));
        report("game", Map.of(FISHING, 10_020_000L));
        tick(Duration.ofMinutes(2));
        assertEquals(1, data.count("IRONMAN"), "remembered");
    }

    @Test
    void privateRuneMetricsStillWorksFromTheHiscoresAlone() {
        before(); // RuneMetrics is private by default in the fake
        theRealOwnerIsCorroboratedAfterLoggingOut();
    }

    @Test
    void aFailedHiscoresReadSettlesNothingAndRetries() {
        before();
        report("game", Map.of(FISHING, 10_012_345L));
        data.boards.put(Board.MAIN, new PublicData.Hiscores.Failed("http 503"));
        tick(Duration.ofSeconds(1));
        assertEquals(1, count(ClaimStatus.UNCHECKED), "never treated as 'not public'");
        assertEquals(0, data.count("RM"), "no point reading the rest");
        Watch w = store.watch(link).orElseThrow();
        assertEquals(clock.instant().plus(XpVerifier.RETRY), w.nextCheckAt());
        before();
        tick(XpVerifier.RETRY);
        assertEquals(1, count(ClaimStatus.PENDING));
    }

    @Test
    void aFailedRuneMetricsReadAcceptsNothing() {
        before();
        data.runeMetrics = new PublicData.RuneMetrics.Failed("timeout");
        report("game", Map.of(FISHING, 10_012_345L));
        tick(Duration.ofSeconds(1));
        assertEquals(1, count(ClaimStatus.UNCHECKED), "RuneMetrics might have shown it, so it isn't a prediction yet");
    }

    @Test
    void retiredRuneMetricsLeavesTheHiscoresToDecide() {
        before();
        data.runeMetrics = new PublicData.RuneMetrics.Off();
        report("game", Map.of(FISHING, 10_012_345L));
        tick(Duration.ofSeconds(1));
        assertEquals(1, count(ClaimStatus.PENDING), "after the sunset, a value the Hiscores don't show yet is a prediction");
    }

    @Test
    void aReportNeverCheckedWithinTheHourIsRefused() {
        before();
        data.boards.put(Board.MAIN, new PublicData.Hiscores.Failed("down"));
        report("game", Map.of(FISHING, 10_012_345L));
        for (int i = 0; i < 14; i++) tick(XpVerifier.RETRY);
        assertEquals(1, count(ClaimStatus.REFUSED_UNCHECKED));
    }

    @Test
    void aPredictionThatNeverPublishesExpires() {
        before();
        report("game", Map.of(FISHING, 10_012_345L));
        tick(Duration.ofSeconds(1));
        for (int i = 0; i < 20; i++) tick(Duration.ofHours(6));
        assertEquals(1, count(ClaimStatus.EXPIRED));
        assertNull(store.watch(link).orElseThrow().nextCheckAt(), "nothing left to wait for");
    }

    // ---------- being polite to Jagex ----------

    @Test
    void checksBackOffAfterTheLastReport() {
        before();
        report("game", Map.of(FISHING, 10_012_345L));
        tick(Duration.ofSeconds(1));
        Instant last = store.watch(link).orElseThrow().lastReportAt();
        assertEquals(last.plus(XpVerifier.AFTER_QUIET), store.watch(link).orElseThrow().nextCheckAt(), "no lobby report: wait for reports to stop");
        clock.advance(XpVerifier.AFTER_QUIET);
        verifier.runDue();
        // The first back-off step (10 minutes after the last report) has already passed, so the next check is a minute away.
        assertEquals(clock.instant().plus(Duration.ofMinutes(1)), store.watch(link).orElseThrow().nextCheckAt());
        clock.advance(Duration.ofMinutes(1));
        verifier.runDue();
        assertEquals(last.plus(XpVerifier.BACK_OFF.get(1)), store.watch(link).orElseThrow().nextCheckAt());
    }

    @Test
    void aLobbyReportChecksSoonerThanSilence() {
        before();
        report("game", Map.of(FISHING, 10_012_345L));
        tick(Duration.ofSeconds(1));
        report("lobby", Map.of());
        Instant at = clock.instant();
        tick(Duration.ofSeconds(1));
        assertEquals(at.plus(XpVerifier.AFTER_LOBBY), store.watch(link).orElseThrow().nextCheckAt());
    }

    @Test
    void aLoginBringsTheNextCheckForward() {
        before();
        report("game", Map.of(FISHING, 10_012_345L));
        tick(Duration.ofSeconds(1));
        verifier.loggedIn(link);
        assertEquals(clock.instant().plus(XpVerifier.AFTER_LOGIN), store.watch(link).orElseThrow().nextCheckAt());
    }

    @Test
    void eachLinkHasADailyBudget() {
        verifier = new XpVerifier(new VerifySettings(true, 2, 100, 3, 600, "test"), store, data, sink, clock);
        before();
        for (int i = 0; i < 6; i++) {
            report("game", Map.of(FISHING, 10_100_000L + i * 10_000L));
            tick(Duration.ofMinutes(2));
        }
        assertEquals(3 * 2, data.count("MAIN") + data.count("RM"), "three checks of two reads each, then nothing until tomorrow");
        Watch w = store.watch(link).orElseThrow();
        assertEquals(Instant.parse("2026-10-23T00:00:00Z"), w.nextCheckAt());
        assertTrue(((Number) verifier.overview().get("budgetRefusals")).intValue() >= 1);
    }

    @Test
    void theServiceWideRateLimitHoldsChecksBack() {
        verifier = new XpVerifier(new VerifySettings(true, 2, 100, 24, 2, "test"), store, data, sink, clock);
        before();
        // Five different players, so no read can be shared.
        for (int i = 0; i < 5; i++) verifier.report(new XpVerifier.Report(UUID.randomUUID(), "Player " + i, "game", Map.of(FISHING, 10_012_345L)));
        clock.advance(Duration.ofSeconds(1));
        assertEquals(2, verifier.runDue());
        assertEquals(0, verifier.runDue(), "the minute's allowance is used up");
        clock.advance(Duration.ofMinutes(1));
        assertEquals(2, verifier.runDue());
    }

    @Test
    void bothSidesOfADisputeShareOneRead() {
        before();
        UUID other = UUID.randomUUID();
        report(link, "game", Map.of(FISHING, 10_012_345L));
        report(other, "game", Map.of(FISHING, 10_020_000L));
        clock.advance(Duration.ofSeconds(1));
        verifier.runDue();
        assertEquals(1, data.count("MAIN"), "the second check reused the first read, which came after both reports");
    }

    @Test
    void aReadIsNeverReusedForAReportMadeAfterIt() {
        before();
        UUID other = UUID.randomUUID();
        report(link, "game", Map.of(FISHING, 10_012_345L));
        clock.advance(Duration.ofSeconds(1));
        verifier.runDue();
        clock.advance(Duration.ofSeconds(5));
        report(other, "game", Map.of(FISHING, 10_012_345L));
        clock.advance(Duration.ofSeconds(1));
        verifier.runDue();
        assertEquals(2, data.count("MAIN"), "a cached read from before the report can't show the value wasn't public then");
    }

    // ---------- off, views, housekeeping ----------

    @Test
    void switchedOffItDoesNothingAtAll() {
        verifier = new XpVerifier(VerifySettings.off(), store, data, sink, clock);
        assertEquals(0, report("game", Map.of(FISHING, 10_012_345L)).accepted());
        assertEquals(0, verifier.runDue());
        assertTrue(data.calls.isEmpty());
        assertEquals("off", verifier.describe(link).get("state"));
    }

    @Test
    void describeShowsCountsButNoValues() {
        before();
        report("game", Map.of(FISHING, 10_012_345L));
        tick(Duration.ofSeconds(1));
        Map<String, Object> d = verifier.describe(link);
        assertEquals("waiting", d.get("state"));
        assertEquals(1, d.get("waiting"));
        assertEquals(2, d.get("needed"));
        assertNotNull(d.get("nextCheckAt"));
        assertFalse(d.toString().contains("10012345"));
        assertEquals("not_started", verifier.describe(UUID.randomUUID()).get("state"));
    }

    @Test
    void sweepRemovesOldSettledClaims() {
        aValueAlreadyOnTheHiscoresIsRefused();
        clock.advance(VerifySettings.KEPT.plusDays(1));
        assertTrue(verifier.sweep() >= 1);
        assertTrue(store.claimCounts(link).isEmpty());
    }

    @Test
    void gameStatesAreNormalised() {
        assertEquals("lobby", XpVerifier.normaliseState("LOBBY"));
        assertEquals("logout", XpVerifier.normaliseState("LOGINSCREEN"));
        assertEquals("game", XpVerifier.normaliseState("anything"));
        assertEquals("game", XpVerifier.normaliseState(null));
    }

    @Test
    void openClaimsComeBackOldestFirst() {
        before();
        report("game", Map.of(FISHING, 10_012_345L));
        clock.advance(Duration.ofSeconds(1));
        report("game", Map.of(FISHING, 10_013_000L));
        List<Claim> open = store.openClaims(link);
        assertEquals(2, open.size());
        assertTrue(open.get(0).claimedAt().isBefore(open.get(1).claimedAt()));
    }
}
