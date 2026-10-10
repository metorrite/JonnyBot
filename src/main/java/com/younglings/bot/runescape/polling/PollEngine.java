package com.younglings.bot.runescape.polling;

import com.younglings.bot.runescape.ProfileResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * The one place RuneMetrics profile requests are made from. Everything that wants a player polled (the recurring
 * schedules, "Update now", a clan sync, an admin button) hands a {@link PollRequest} to {@link #submit}, and a single
 * worker drains the queue at the pace {@link RateBucket} allows. That is what turns the bot's several independent
 * polling loops into one coordinated system:
 * <ul>
 *   <li><b>Priority.</b> The next request sent is always the best-priority one that is eligible, so an "Update now"
 *       goes in front of a long background pass, and a tier added later slots in without any other change.</li>
 *   <li><b>One poll per player.</b> The queue holds a player once; a second request for a name already waiting is
 *       merged into it (and takes the better priority), and one that arrives while that player is being polled just
 *       waits for the same answer.</li>
 *   <li><b>Recent polls count.</b> Before a request is sent, the player's last poll is looked up; if it is newer than
 *       the request's {@code maxAge} the request is answered from the stored snapshot instead. So a player who just
 *       pressed Update is skipped by the schedule that would have reached them next, and vice versa.</li>
 *   <li><b>One budget.</b> Every request spends from the same bucket, which backs off on a 429 and recovers when the
 *       limit stops biting.</li>
 * </ul>
 * A request that gets a 429 goes back in the queue with a delay and is tried again (a few times), instead of the
 * caller treating it like any other failure.
 * <p>
 * {@link #tick()} does one non-blocking step against an injectable clock, which is what the tests drive; the worker
 * thread is only a loop around it that sleeps when {@code tick} says there is nothing to do yet.
 */
public final class PollEngine {
    private static final Logger log = LoggerFactory.getLogger(PollEngine.class);

    /** Requests a rate-limited poll is attempted at most (the first send counts). */
    static final int MAX_ATTEMPTS = 4;
    /** Somebody is waiting on an interactive poll, so it gives up sooner than a background one would. */
    static final int INTERACTIVE_MAX_ATTEMPTS = 3;
    static final long RETRY_BASE_MS = 60_000;
    static final long RETRY_MAX_MS = 10 * 60_000;
    /** A freshness lookup is repeated no more often than this for an entry that is waiting on a token. */
    private static final long FRESH_CHECK_REUSE_MS = 10_000;
    private static final long REQUEST_LOG_MS = 60 * 60_000L;
    private static final int RECENT_POLLS_PRUNE_AT = 20_000;
    private static final long RECENT_POLLS_KEEP_MS = 24 * 60 * 60_000L;

    /** Fetches, saves and announces one player. Only the engine calls this: everything else goes through the queue. */
    @FunctionalInterface
    public interface ProfilePoller {
        ProfileResult poll(String rsn) throws Exception;
    }

    /** When a player was last polled, from wherever that is recorded (epoch millis), or empty if never. */
    @FunctionalInterface
    public interface FreshnessSource {
        OptionalLong lastPolledAtMs(String rsn);
    }

    /** A point-in-time view for the status page. */
    public record Status(int queued, Map<PollPriority, Integer> queuedByPriority, String inFlight, double tokens, double burst,
                         long intervalMs, int multiplier, long polled, long reusedRecent, long merged, long rateLimited,
                         long gaveUp, long failed, long requestsLastMinute, long requestsLast10Minutes, long oldestWaitingMs) {}

    private final ProfilePoller poller;
    private final FreshnessSource freshness;
    private final RateBucket bucket;
    private final LongSupplier clock;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wake = lock.newCondition();
    private final PollQueue queue = new PollQueue();
    private final Map<String, PollQueue.Item> inFlight = new HashMap<>();
    private final Map<String, Long> recentPolls = new HashMap<>();
    private final Deque<Long> requestLog = new ArrayDeque<>();
    private final Map<String, Long> enqueuedAt = new HashMap<>();
    private boolean dirty;
    private volatile boolean running = true;
    private Thread worker;

    private final AtomicLong polled = new AtomicLong();
    private final AtomicLong reusedRecent = new AtomicLong();
    private final AtomicLong merged = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicLong gaveUp = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public PollEngine(ProfilePoller poller, FreshnessSource freshness, RateBucket bucket, LongSupplier clock) {
        this.poller = poller;
        this.freshness = freshness;
        this.bucket = bucket;
        this.clock = clock;
    }

    /** Starts the worker thread. Tests skip this and call {@link #tick()} themselves. */
    public void start() {
        lock.lock();
        try {
            if (worker != null) return;
            worker = new Thread(this::runLoop, "poll-coordinator");
            worker.setDaemon(true);
            worker.start();
        } finally {
            lock.unlock();
        }
    }

    /** Stops the worker and answers everything still waiting as cancelled. */
    public void stop() {
        running = false;
        List<CompletableFuture<PollOutcome>> orphaned = new ArrayList<>();
        lock.lock();
        try {
            for (PollQueue.Item item : queue.all()) {
                queue.remove(item);
                orphaned.addAll(item.waiters);
            }
            wake.signalAll();
        } finally {
            lock.unlock();
        }
        orphaned.forEach(waiter -> waiter.complete(PollOutcome.cancelled()));
    }

    // ---------------------------------------------------------------- submitting

    public CompletableFuture<PollOutcome> submit(PollRequest request) {
        return submitAll(List.of(request)).get(0);
    }

    /** Submits many requests under one lock, so a tier of hundreds doesn't contend with the worker once per player. */
    public List<CompletableFuture<PollOutcome>> submitAll(List<PollRequest> requests) {
        List<CompletableFuture<PollOutcome>> futures = new ArrayList<>(requests.size());
        if (!running) {
            for (int i = 0; i < requests.size(); i++) futures.add(CompletableFuture.completedFuture(PollOutcome.cancelled()));
            return futures;
        }

        lock.lock();
        try {
            long now = clock.getAsLong();
            for (PollRequest request : requests) {
                CompletableFuture<PollOutcome> future = new CompletableFuture<>();
                futures.add(future);

                PollQueue.Item flying = inFlight.get(PollQueue.key(request.rsn()));
                if (flying != null) {
                    // Already on its way to RuneMetrics: this request gets the same answer, however urgent it is.
                    flying.waiters.add(future);
                    merged.incrementAndGet();
                    continue;
                }
                PollQueue.Item existing = queue.get(request.rsn());
                if (existing != null) {
                    queue.merge(existing, request, now);
                    existing.waiters.add(future);
                    merged.incrementAndGet();
                } else {
                    PollQueue.Item item = queue.add(request, now);
                    item.waiters.add(future);
                    enqueuedAt.put(item.key, now);
                }
            }
            dirty = true;
            wake.signalAll();
        } finally {
            lock.unlock();
        }
        return futures;
    }

    // ---------------------------------------------------------------- the worker

    private void runLoop() {
        while (running) {
            long waitMs;
            try {
                waitMs = tick();
            } catch (Exception e) {
                log.error("Poll coordinator step failed", e);
                waitMs = 1_000;
            }
            if (waitMs == 0) continue;

            lock.lock();
            try {
                if (!dirty && running) {
                    if (waitMs < 0) wake.await();
                    else wake.await(waitMs, TimeUnit.MILLISECONDS);
                }
                dirty = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * One step: send the next request if there is one that is eligible and a token to pay for it, answer one from the
     * stored snapshot if that is enough, or report how long to wait. Returns 0 if it did something (call again),
     * a positive number of milliseconds to wait for a token or a delayed entry, or -1 to wait for a submission.
     */
    long tick() {
        PollQueue.Item item;
        long now;
        lock.lock();
        try {
            now = clock.getAsLong();
            queue.promoteDue(now);
            item = queue.peekReady();
            if (item == null) {
                OptionalLong next = queue.nextWakeup();
                return next.isPresent() ? Math.max(1, next.getAsLong() - now) : -1;
            }
        } finally {
            lock.unlock();
        }

        if (answeredFromRecentPoll(item, now)) return 0;

        lock.lock();
        try {
            long wait = bucket.waitMs();
            if (wait > 0) return wait;
            if (!queue.remove(item)) return 0; // merged away or cancelled while the freshness lookup ran
            bucket.consume();
            inFlight.put(item.key, item);
            item.attempts++;
            noteRequest(now);
        } finally {
            lock.unlock();
        }

        send(item);
        return 0;
    }

    /** Completes {@code item} without a request if its player was polled recently enough. */
    private boolean answeredFromRecentPoll(PollQueue.Item item, long now) {
        if (item.maxAgeMs <= 0) return false;
        if (item.freshCheckedAtMs != 0 && now - item.freshCheckedAtMs < FRESH_CHECK_REUSE_MS) return false;

        long lastPolled = lastPolledAt(item);
        List<CompletableFuture<PollOutcome>> waiters;
        lock.lock();
        try {
            item.freshCheckedAtMs = now;
            if (lastPolled <= 0 || now - lastPolled >= item.maxAgeMs) return false;
            if (!queue.remove(item)) return false;
            waiters = new ArrayList<>(item.waiters);
            enqueuedAt.remove(item.key);
        } finally {
            lock.unlock();
        }
        reusedRecent.incrementAndGet();
        log.debug("Skipped polling '{}' ({}): polled {}s ago, within the {}s this request allows.",
                item.rsn, item.source, (now - lastPolled) / 1000, item.maxAgeMs / 1000);
        waiters.forEach(waiter -> waiter.complete(PollOutcome.reused()));
        return true;
    }

    /** The last poll of this player: what this engine saw if that is the newer, else what the database remembers (so a restart doesn't forget). */
    private long lastPolledAt(PollQueue.Item item) {
        Long seen;
        lock.lock();
        try {
            seen = recentPolls.get(item.key);
        } finally {
            lock.unlock();
        }
        long stored = 0;
        try {
            OptionalLong fromSource = freshness.lastPolledAtMs(item.rsn);
            if (fromSource.isPresent()) stored = fromSource.getAsLong();
        } catch (Exception e) {
            log.warn("Couldn't look up when '{}' was last polled; polling it anyway.", item.rsn, e);
        }
        return Math.max(seen == null ? 0 : seen, stored);
    }

    private void send(PollQueue.Item item) {
        ProfileResult result = null;
        boolean threw = false;
        try {
            result = poller.poll(item.rsn);
        } catch (Exception e) {
            threw = true;
            log.warn("Failed to poll '{}' ({})", item.rsn, item.source, e);
        }

        boolean wasRateLimited = result instanceof ProfileResult.RateLimited;
        PollOutcome outcome = null;
        List<CompletableFuture<PollOutcome>> waiters = null;

        lock.lock();
        try {
            long now = clock.getAsLong();
            bucket.recordResult(wasRateLimited);

            if (wasRateLimited) {
                rateLimited.incrementAndGet();
                int limit = item.priority == PollPriority.INTERACTIVE ? INTERACTIVE_MAX_ATTEMPTS : MAX_ATTEMPTS;
                if (item.attempts < limit) {
                    inFlight.remove(item.key);
                    long delay = retryDelayMs(item, (ProfileResult.RateLimited) result);
                    queue.requeue(item, now + delay, now);
                    dirty = true;
                    wake.signalAll();
                    log.info("RuneMetrics rate-limited the poll of '{}' ({}); trying again in {}s (attempt {} of {}). Pace is now one request per {}s.",
                            item.rsn, item.source, delay / 1000, item.attempts + 1, limit, bucket.intervalMs() / 1000);
                    return;
                }
                gaveUp.incrementAndGet();
                outcome = PollOutcome.rateLimited(result, item.attempts);
            } else if (threw) {
                failed.incrementAndGet();
                outcome = PollOutcome.failed(item.attempts);
            } else {
                polled.incrementAndGet();
                // Found, private and unknown all mean RuneMetrics gave a definite answer; only a transient failure should be tried again soon.
                if (!(result instanceof ProfileResult.Unavailable)) rememberPolled(item.key, now);
                outcome = PollOutcome.polled(result, item.attempts);
            }

            inFlight.remove(item.key);
            enqueuedAt.remove(item.key);
            waiters = new ArrayList<>(item.waiters);
        } finally {
            lock.unlock();
        }
        PollOutcome finalOutcome = outcome;
        waiters.forEach(waiter -> waiter.complete(finalOutcome));
    }

    private long retryDelayMs(PollQueue.Item item, ProfileResult.RateLimited limited) {
        // Someone waiting on a button gets the bucket's own (already slowed) pace; background work steps back further each time.
        long base = item.priority == PollPriority.INTERACTIVE ? 0 : Math.min(RETRY_BASE_MS << Math.min(item.attempts - 1, 4), RETRY_MAX_MS);
        long hint = limited.retryAfter() == null ? 0 : limited.retryAfter().toMillis();
        return Math.max(base, hint);
    }

    private void rememberPolled(String key, long now) {
        recentPolls.put(key, now);
        if (recentPolls.size() > RECENT_POLLS_PRUNE_AT) recentPolls.values().removeIf(at -> now - at > RECENT_POLLS_KEEP_MS);
    }

    private void noteRequest(long now) {
        requestLog.addLast(now);
        while (!requestLog.isEmpty() && now - requestLog.peekFirst() > REQUEST_LOG_MS) requestLog.removeFirst();
    }

    // ---------------------------------------------------------------- status

    public Status status() {
        lock.lock();
        try {
            long now = clock.getAsLong();
            long lastMinute = 0;
            long lastTen = 0;
            for (long at : requestLog) {
                if (now - at <= 60_000) lastMinute++;
                if (now - at <= 10 * 60_000L) lastTen++;
            }
            long oldest = 0;
            for (PollQueue.Item item : queue.all()) {
                Long queuedAt = enqueuedAt.get(item.key);
                if (queuedAt != null && item.notBeforeMs <= now) oldest = Math.max(oldest, now - Math.max(queuedAt, item.notBeforeMs));
            }
            String flying = inFlight.values().stream().findFirst().map(i -> i.rsn).orElse(null);
            return new Status(queue.size(), new EnumMap<>(queue.countsByPriority()), flying, bucket.tokens(), bucket.capacity(),
                    bucket.intervalMs(), bucket.multiplier(), polled.get(), reusedRecent.get(), merged.get(), rateLimited.get(),
                    gaveUp.get(), failed.get(), lastMinute, lastTen, oldest);
        } finally {
            lock.unlock();
        }
    }
}
