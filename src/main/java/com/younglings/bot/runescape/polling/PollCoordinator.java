package com.younglings.bot.runescape.polling;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.RuneScapeStatsService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The bot's single front door to RuneMetrics profile polling: everything that wants a player polled calls this
 * instead of {@link RuneScapeStatsService}, so there is one queue, one rate budget, and no way for two parts of the
 * bot to poll the same player twice in a row (see {@link PollEngine} for how).
 * <p>
 * Two kinds of work go in:
 * <ul>
 *   <li><b>One-off requests</b> ({@link #submit}, {@link #pollAndWait}): "Update now", a lookup, an admin refresh,
 *       a clan sync. Interactive ones go to the front of the line.</li>
 *   <li><b>Recurring jobs</b> ({@link #schedule}): a named {@link PollJob} that resubmits its tier on a period. The
 *       clan and linked tiers in {@code RosterPollScheduler} are two of these.</li>
 * </ul>
 * Polling always works, whatever {@code RUNESCAPE_AUTO_POLL_ENABLED} says: that only decides whether the recurring
 * jobs are registered, not whether a person can ask for a poll.
 */
@BService
public class PollCoordinator {
    private static final Logger log = LoggerFactory.getLogger(PollCoordinator.class);

    /** The longest a button press waits for its answer before telling the person it is still working. */
    public static final Duration INTERACTIVE_WAIT = Duration.ofSeconds(90);

    private final PollEngine engine;
    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService jobRunner = Executors.newSingleThreadScheduledExecutor(
            (ThreadFactory) runnable -> {
                Thread thread = new Thread(runnable, "poll-jobs");
                thread.setDaemon(true);
                return thread;
            });

    private static final class JobState {
        final PollJob job;
        volatile long lastRunAt;
        volatile int lastSubmitted;
        ScheduledFuture<?> schedule;

        JobState(PollJob job) {
            this.job = job;
        }
    }

    /** What a recurring job last did, for the status page. */
    public record JobStatus(String name, PollPriority priority, long periodSeconds, long spreadSeconds, long lastRunAtMs, int lastSubmitted) {}

    public record Status(PollEngine.Status engine, List<JobStatus> jobs) {}

    public PollCoordinator(RuneScapeStatsService statsService, PlayerLinkRepository repository, BotConfig config) {
        this.engine = new PollEngine(
                statsService::fetchAndStore,
                rsn -> {
                    var latest = repository.getLatestSnapshot(rsn);
                    return latest == null ? OptionalLong.empty() : OptionalLong.of(latest.snapshotAt().toInstant().toEpochMilli());
                },
                RateBucket.perMinute(config.getRunescapePollRequestsPerMinute(), config.getRunescapePollBurst(), System::currentTimeMillis),
                System::currentTimeMillis);
        engine.start();
        log.info("Poll coordinator ready: up to {} RuneMetrics requests a minute, bursts of {}.",
                config.getRunescapePollRequestsPerMinute(), config.getRunescapePollBurst());
    }

    // ---------------------------------------------------------------- one-off requests

    public CompletableFuture<PollOutcome> submit(PollRequest request) {
        return engine.submit(request);
    }

    public List<CompletableFuture<PollOutcome>> submitAll(List<PollRequest> requests) {
        return engine.submitAll(requests);
    }

    /** Submits one request and waits for it, for callers that are handling a button press and need the answer to reply with. */
    public PollOutcome pollAndWait(PollRequest request, Duration timeout) {
        return awaitAll(List.of(engine.submit(request)), timeout).get(0);
    }

    /** {@link #pollAndWait} for a person who pressed something: front of the line, a real poll, {@link #INTERACTIVE_WAIT} patience. */
    public PollOutcome pollNow(String rsn, String source) {
        return pollAndWait(PollRequest.interactive(rsn, source), INTERACTIVE_WAIT);
    }

    /** Waits for all of {@code futures}, within {@code timeout} overall; any still running then are reported as timed out (they carry on regardless). */
    public List<PollOutcome> awaitAll(List<CompletableFuture<PollOutcome>> futures, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<PollOutcome> outcomes = new ArrayList<>(futures.size());
        boolean interrupted = false;
        for (CompletableFuture<PollOutcome> future : futures) {
            if (interrupted) {
                outcomes.add(PollOutcome.timedOut());
                continue;
            }
            try {
                outcomes.add(future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                outcomes.add(PollOutcome.timedOut());
            } catch (ExecutionException e) {
                outcomes.add(PollOutcome.failed(0));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted = true;
                outcomes.add(PollOutcome.timedOut());
            }
        }
        return outcomes;
    }

    // ---------------------------------------------------------------- recurring jobs

    /** Registers (or replaces, by name) a recurring job. */
    public void schedule(PollJob job) {
        JobState state = new JobState(job);
        JobState previous = jobs.put(job.name(), state);
        if (previous != null && previous.schedule != null) previous.schedule.cancel(false);

        state.schedule = jobRunner.scheduleAtFixedRate(() -> runJob(state), job.initialDelay().toMillis(), job.period().toMillis(), TimeUnit.MILLISECONDS);
        log.info("Poll job '{}' registered: {} priority, every {}, spread over {}, first run in {}.",
                job.name(), job.priority(), job.period(), job.spreadOver(), job.initialDelay());
    }

    public void cancel(String name) {
        JobState state = jobs.remove(name);
        if (state != null && state.schedule != null) state.schedule.cancel(false);
    }

    private void runJob(JobState state) {
        PollJob job = state.job;
        try {
            List<String> rsns = job.targets().get();
            state.lastRunAt = System.currentTimeMillis();
            state.lastSubmitted = rsns.size();
            if (rsns.isEmpty()) return;

            engine.submitAll(job.requestsFor(rsns));
            log.info("Poll job '{}' queued {} player(s), spread over {}.", job.name(), rsns.size(), job.spreadOver());
        } catch (Exception e) {
            // Never let one bad run cancel the schedule: scheduleAtFixedRate stops a task that throws.
            log.error("Poll job '{}' failed to queue its players", job.name(), e);
        }
    }

    // ---------------------------------------------------------------- status

    public Status status() {
        List<JobStatus> statuses = new ArrayList<>();
        for (JobState state : jobs.values()) {
            PollJob job = state.job;
            statuses.add(new JobStatus(job.name(), job.priority(), job.period().toSeconds(), job.spreadOver().toSeconds(), state.lastRunAt, state.lastSubmitted));
        }
        statuses.sort(java.util.Comparator.comparing(JobStatus::priority).thenComparing(JobStatus::name));
        return new Status(engine.status(), statuses);
    }
}
