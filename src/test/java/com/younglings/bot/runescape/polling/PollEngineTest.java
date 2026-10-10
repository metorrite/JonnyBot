package com.younglings.bot.runescape.polling;

import com.younglings.bot.runescape.ProfileResult;
import com.younglings.bot.runescape.RuneScapeProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives {@link PollEngine#tick()} directly against a fake clock and a scripted poller, so nothing here sleeps. */
class PollEngineTest {
    private static final Duration HOUR = Duration.ofHours(1);

    private final AtomicLong clock = new AtomicLong(10_000_000);
    private final List<String> polled = new ArrayList<>();
    /** Scripted answers per lower-cased name; a name without one gets a found profile. */
    private final Map<String, Deque<ProfileResult>> script = new HashMap<>();
    /** What the "database" says about when each player was last polled (epoch millis). */
    private final Map<String, Long> storedPollTimes = new HashMap<>();
    private Consumer<String> duringPoll = rsn -> {};

    private RateBucket bucket;
    private PollEngine engine;

    @BeforeEach
    void setUp() {
        bucket = new RateBucket(100, 1_000, clock::get); // roomy by default; tests of pacing build their own
        engine = engineWith(bucket);
    }

    private PollEngine engineWith(RateBucket bucket) {
        return new PollEngine(rsn -> {
            polled.add(rsn);
            duringPoll.accept(rsn);
            Deque<ProfileResult> answers = script.get(rsn.toLowerCase(Locale.ROOT));
            return answers != null && !answers.isEmpty() ? answers.pollFirst() : found();
        }, rsn -> {
            Long at = storedPollTimes.get(rsn.toLowerCase(Locale.ROOT));
            return at == null ? OptionalLong.empty() : OptionalLong.of(at);
        }, bucket, clock::get);
    }

    private static ProfileResult found() {
        return new ProfileResult.Found(new RuneScapeProfile("x", 0, 0L, 3, 0, 0, 0, List.of(), List.of()));
    }

    private void answers(String rsn, ProfileResult... results) {
        script.computeIfAbsent(rsn.toLowerCase(Locale.ROOT), k -> new ArrayDeque<>()).addAll(List.of(results));
    }

    /** Ticks until the engine has nothing more it can do right now, and returns what it then wants to wait. */
    private long drain() {
        long wait;
        int guard = 0;
        while ((wait = engine.tick()) == 0) {
            if (++guard > 1000) throw new AssertionError("engine never went idle");
        }
        return wait;
    }

    private CompletableFuture<PollOutcome> clan(String rsn) {
        return engine.submit(PollRequest.background(rsn, PollPriority.CLAN, Duration.ZERO, "test"));
    }

    // ---------------------------------------------------------------- priority

    @Test
    void theBestPriorityIsPolledFirstWhateverTheSubmitOrder() {
        engine.submit(PollRequest.background("linked", PollPriority.LINKED, Duration.ZERO, "t"));
        engine.submit(PollRequest.background("clan", PollPriority.CLAN, Duration.ZERO, "t"));
        engine.submit(PollRequest.interactive("button", "t"));

        drain();

        assertEquals(List.of("button", "clan", "linked"), polled);
    }

    @Test
    void anUpdateNowJumpsAheadOfBackgroundWorkThatWasAlreadyWaiting() {
        // Constrained to one request, so the queue is visibly mid-pass when the button is pressed.
        engine = engineWith(new RateBucket(1, 10_000, clock::get));
        for (int i = 0; i < 5; i++) clan("clan" + i);

        long wait = drain();
        assertEquals(List.of("clan0"), polled);
        assertTrue(wait > 0);

        engine.submit(PollRequest.interactive("button", "update now"));
        clock.addAndGet(wait);
        drain();

        assertEquals(List.of("clan0", "button"), polled);
    }

    // ---------------------------------------------------------------- one poll per player

    @Test
    void twoRequestsForOnePlayerMakeOnePollAndBothHearTheAnswer() {
        CompletableFuture<PollOutcome> first = clan("Jonny Young");
        CompletableFuture<PollOutcome> second = engine.submit(PollRequest.background("jonny young", PollPriority.LINKED, Duration.ZERO, "t"));

        drain();

        assertEquals(List.of("Jonny Young"), polled);
        assertEquals(PollOutcome.Status.POLLED, first.join().status());
        assertEquals(PollOutcome.Status.POLLED, second.join().status());
        assertEquals(1, engine.status().merged());
    }

    @Test
    void aMergedRequestTakesTheHigherPriority() {
        clan("someone");
        clan("other");
        engine.submit(PollRequest.interactive("OTHER", "button"));

        drain();

        assertEquals(List.of("other", "someone"), polled);
    }

    @Test
    void aRequestForAPlayerBeingPolledRightNowSharesThatPollInsteadOfStartingAnother() {
        List<CompletableFuture<PollOutcome>> late = new ArrayList<>();
        duringPoll = rsn -> late.add(engine.submit(PollRequest.interactive(rsn, "button")));

        CompletableFuture<PollOutcome> first = clan("a");
        drain();

        assertEquals(List.of("a"), polled);
        assertEquals(PollOutcome.Status.POLLED, late.get(0).join().status());
        assertTrue(first.isDone());
    }

    // ---------------------------------------------------------------- recent polls

    @Test
    void aPlayerPolledRecentlyEnoughIsNotPolledAgain() {
        storedPollTimes.put("a", clock.get() - Duration.ofMinutes(10).toMillis());

        CompletableFuture<PollOutcome> outcome = engine.submit(PollRequest.background("a", PollPriority.CLAN, HOUR, "t"));
        drain();

        assertEquals(List.of(), polled);
        assertEquals(PollOutcome.Status.REUSED_RECENT, outcome.join().status());
        assertTrue(outcome.join().hasFreshData());
        assertEquals(1, engine.status().reusedRecent());
    }

    @Test
    void aSkippedPlayerCostsNoRequestBudget() {
        storedPollTimes.put("a", clock.get() - 1_000);
        RateBucket tight = new RateBucket(1, 60_000, clock::get);
        engine = engineWith(tight);

        engine.submit(PollRequest.background("a", PollPriority.CLAN, HOUR, "t"));
        drain();

        assertEquals(1.0, tight.tokens(), 1e-9);
    }

    @Test
    void aPlayerPolledLongerAgoThanTheRequestAllowsIsPolled() {
        storedPollTimes.put("a", clock.get() - Duration.ofHours(2).toMillis());

        engine.submit(PollRequest.background("a", PollPriority.CLAN, HOUR, "t"));
        drain();

        assertEquals(List.of("a"), polled);
    }

    @Test
    void aRequiredPollHappensEvenIfThePlayerWasPolledASecondAgo() {
        storedPollTimes.put("a", clock.get() - 1_000);

        engine.submit(PollRequest.interactive("a", "update now"));
        drain();

        assertEquals(List.of("a"), polled);
    }

    @Test
    void anUpdateNowMakesTheSchedulesNextPollOfThatPlayerUnnecessary() {
        engine.submit(PollRequest.interactive("a", "update now"));
        drain();
        clock.addAndGet(Duration.ofMinutes(20).toMillis());

        CompletableFuture<PollOutcome> scheduled = engine.submit(PollRequest.background("a", PollPriority.CLAN, HOUR, "clan players"));
        drain();

        assertEquals(List.of("a"), polled);
        assertEquals(PollOutcome.Status.REUSED_RECENT, scheduled.join().status());
    }

    @Test
    void aScheduledPollMakesALaterBackgroundPassOverTheSamePlayerUnnecessary() {
        clan("a");
        drain();

        CompletableFuture<PollOutcome> again = engine.submit(PollRequest.background("a", PollPriority.LINKED, HOUR, "linked"));
        drain();

        assertEquals(List.of("a"), polled);
        assertEquals(PollOutcome.Status.REUSED_RECENT, again.join().status());
    }

    @Test
    void aPrivateProfileCountsAsPolledButATransientFailureDoesNot() {
        answers("hidden", new ProfileResult.Private());
        answers("flaky", new ProfileResult.Unavailable());

        engine.submit(PollRequest.interactive("hidden", "t"));
        engine.submit(PollRequest.interactive("flaky", "t"));
        drain();
        polled.clear();

        CompletableFuture<PollOutcome> hidden = engine.submit(PollRequest.background("hidden", PollPriority.CLAN, HOUR, "t"));
        engine.submit(PollRequest.background("flaky", PollPriority.CLAN, HOUR, "t"));
        drain();

        assertEquals(PollOutcome.Status.REUSED_RECENT, hidden.join().status());
        assertEquals(List.of("flaky"), polled);
    }

    // ---------------------------------------------------------------- pacing

    @Test
    void nothingIsSentWhileTheBudgetIsEmptyAndTheEngineSaysHowLongToWait() {
        engine = engineWith(new RateBucket(1, 6_000, clock::get));
        clan("a");
        clan("b");

        long wait = drain();

        assertEquals(List.of("a"), polled);
        assertEquals(6_000, wait);

        clock.addAndGet(wait);
        drain();
        assertEquals(List.of("a", "b"), polled);
    }

    @Test
    void aSpreadRequestIsNotEligibleUntilItsTime() {
        engine.submit(PollRequest.background("early", PollPriority.CLAN, Duration.ZERO, "t"));
        engine.submit(PollRequest.background("late", PollPriority.CLAN, Duration.ZERO, "t").withDelay(Duration.ofMinutes(5)));

        long wait = drain();

        assertEquals(List.of("early"), polled);
        assertEquals(Duration.ofMinutes(5).toMillis(), wait);
        clock.addAndGet(wait);
        drain();
        assertEquals(List.of("early", "late"), polled);
    }

    @Test
    void anIdleEngineWaitsForASubmission() {
        assertEquals(-1, engine.tick());
    }

    // ---------------------------------------------------------------- rate limits

    @Test
    void aRateLimitedBackgroundPollIsRetriedLaterAndTheCallerStillGetsTheAnswer() {
        answers("a", new ProfileResult.RateLimited(null), found());

        CompletableFuture<PollOutcome> outcome = clan("a");
        long wait = drain();

        assertEquals(List.of("a"), polled);
        assertFalse(outcome.isDone());
        assertEquals(2, bucket.multiplier());
        assertTrue(wait >= PollEngine.RETRY_BASE_MS - 1);

        clock.addAndGet(PollEngine.RETRY_BASE_MS);
        drain();

        assertEquals(List.of("a", "a"), polled);
        assertEquals(PollOutcome.Status.POLLED, outcome.join().status());
        assertEquals(2, outcome.join().attempts());
        assertEquals(1, engine.status().rateLimited());
    }

    @Test
    void theServersRetryAfterIsNeverWaitedLessThan() {
        answers("a", new ProfileResult.RateLimited(Duration.ofMinutes(5)), found());

        clan("a");
        long wait = drain();

        assertTrue(wait >= Duration.ofMinutes(5).toMillis() - 1);
    }

    @Test
    void aPersonWaitingIsRetriedOnTheBucketsPaceNotABackgroundBackoff() {
        answers("a", new ProfileResult.RateLimited(null), found());

        CompletableFuture<PollOutcome> outcome = engine.submit(PollRequest.interactive("a", "update now"));
        long wait = drain();

        assertTrue(wait < PollEngine.RETRY_BASE_MS, "waited " + wait);
        clock.addAndGet(wait);
        drain();

        assertEquals(PollOutcome.Status.POLLED, outcome.join().status());
    }

    @Test
    void whenTheRetriesRunOutTheCallerIsToldItWasRateLimited() {
        answers("a", new ProfileResult.RateLimited(null), new ProfileResult.RateLimited(null), new ProfileResult.RateLimited(null));

        CompletableFuture<PollOutcome> outcome = engine.submit(PollRequest.interactive("a", "update now"));
        for (int i = 0; i < PollEngine.INTERACTIVE_MAX_ATTEMPTS && !outcome.isDone(); i++) {
            clock.addAndGet(drain());
            drain();
        }

        assertEquals(PollOutcome.Status.RATE_LIMITED, outcome.join().status());
        assertEquals(PollEngine.INTERACTIVE_MAX_ATTEMPTS, outcome.join().attempts());
        assertFalse(outcome.join().hasFreshData());
        assertEquals(1, engine.status().gaveUp());
    }

    @Test
    void aRateLimitSlowsEveryoneNotJustThePlayerThatWasLimited() {
        RateBucket real = new RateBucket(1, 6_000, clock::get);
        engine = engineWith(real);
        answers("a", new ProfileResult.RateLimited(null));

        clan("a");
        clan("b");
        long wait = drain();

        assertEquals(12_000, real.intervalMs());
        assertTrue(wait >= 12_000);
    }

    // ---------------------------------------------------------------- failures and shutdown

    @Test
    void aPollThatThrowsEndsAsFailedWithoutStoppingTheQueue() {
        engine = new PollEngine(rsn -> {
            if (rsn.equals("boom")) throw new IllegalStateException("database down");
            polled.add(rsn);
            return found();
        }, rsn -> OptionalLong.empty(), bucket, clock::get);

        CompletableFuture<PollOutcome> boom = clan("boom");
        CompletableFuture<PollOutcome> fine = clan("fine");
        drain();

        assertEquals(PollOutcome.Status.FAILED, boom.join().status());
        assertEquals(PollOutcome.Status.POLLED, fine.join().status());
        assertEquals(1, engine.status().failed());
    }

    @Test
    void stoppingCancelsEverythingStillWaiting() {
        CompletableFuture<PollOutcome> waiting = clan("a");

        engine.stop();

        assertEquals(PollOutcome.Status.CANCELLED, waiting.join().status());
        assertEquals(PollOutcome.Status.CANCELLED, clan("b").join().status());
    }

    @Test
    void theStatusReportsWhatIsWaitingPerPriorityAndHowBusyTheEngineHasBeen() {
        engine = engineWith(new RateBucket(1, 6_000, clock::get));
        clan("a");
        clan("b");
        engine.submit(PollRequest.background("c", PollPriority.LINKED, Duration.ZERO, "t"));
        drain();

        PollEngine.Status status = engine.status();

        assertEquals(2, status.queued());
        assertEquals(1, status.queuedByPriority().get(PollPriority.CLAN));
        assertEquals(1, status.queuedByPriority().get(PollPriority.LINKED));
        assertEquals(1, status.polled());
        assertEquals(1, status.requestsLastMinute());
    }
}
