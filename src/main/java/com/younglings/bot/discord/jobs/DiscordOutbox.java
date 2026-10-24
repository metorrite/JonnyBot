package com.younglings.bot.discord.jobs;

import com.younglings.bot.discord.jobs.DiscordJobs.Fail;
import com.younglings.bot.discord.jobs.DiscordJobs.Next;
import com.younglings.bot.discord.jobs.DiscordJobs.Retry;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The Discord job outbox (T-456): website changes that end in a Discord request are saved as a {@link DiscordJob} and answered at once; this
 * runs them in the background on its own {@link BotScheduler#lane}, one at a time, oldest first. A rate limit puts the job back as
 * "retrying" until Discord allows it (never an error); a fault that may pass is tried again with growing waits; Discord's plain "no" ends it
 * as failed with the reason in words. The website polls {@code /internal/discord-jobs} for the state.
 */
@BService
public class DiscordOutbox {
    private static final Logger log = LoggerFactory.getLogger(DiscordOutbox.class);
    /** How many due jobs one pass takes. */
    static final int BATCH = 10;
    /** Finished jobs are kept this long, so a page opened later can still say how a change ended. */
    static final Duration KEEP_FINISHED = Duration.ofDays(3);

    private final DiscordJobRepository repository;
    private final Map<String, DiscordJobHandler> handlers = new HashMap<>();
    private final ScheduledExecutorService lane = BotScheduler.lane("discord-jobs");
    private volatile JDA jda;

    public DiscordOutbox(DiscordJobRepository repository, List<DiscordJobHandler> handlers) {
        this.repository = repository;
        for (DiscordJobHandler handler : handlers) this.handlers.put(handler.kind(), handler);
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        jda = event.getJda();
        lane.execute(() -> {
            int recovered = repository.recover(handlers.values().stream().filter(DiscordJobHandler::idempotent).map(DiscordJobHandler::kind).toList());
            if (recovered > 0) log.info("Discord jobs: {} left running by the last run were put back or ended", recovered);
        });
        lane.scheduleWithFixedDelay(this::drain, 2, 1, TimeUnit.SECONDS);
        lane.scheduleWithFixedDelay(() -> repository.sweep(Instant.now().minus(KEEP_FINISHED)), 10, 60 * 60, TimeUnit.SECONDS);
    }

    /**
     * Saves a job and starts it soon. Answers straight away: nothing here talks to Discord. A job still waiting with the same {@code dedupe} key
     * is replaced by this one (the newest change of the same thing wins).
     */
    public DiscordJob enqueue(String kind, long guildId, long actorId, String dedupe, DataObject payload) {
        if (!handlers.containsKey(kind)) throw new IllegalArgumentException("No handler for Discord job kind " + kind);
        DiscordJob job = repository.enqueue(kind, guildId, actorId, dedupe, payload);
        if (jda != null) lane.execute(this::drain);
        return job;
    }

    /** The jobs with these ids that belong to this server and person. */
    public List<DiscordJob> find(Collection<Long> ids, long guildId, long actorId) {
        return repository.find(ids, guildId, actorId);
    }

    public Optional<DiscordJob> get(long id) {
        return repository.get(id);
    }

    /** Sends one of this person's failed jobs again (the website's Retry). */
    public boolean retry(long id, long guildId, long actorId) {
        boolean again = repository.requeue(id, guildId, actorId);
        if (again && jda != null) lane.execute(this::drain);
        return again;
    }

    private void drain() {
        JDA current = jda;
        if (current != null) drain(current);
    }

    /** Runs every job that is due, a batch at a time. On the lane only (or in a test). */
    void drain(JDA jda) {
        while (true) {
            List<DiscordJob> due = repository.claimDue(BATCH);
            for (DiscordJob job : due) runOne(jda, job);
            if (due.size() < BATCH) return;
        }
    }

    void runOne(JDA jda, DiscordJob job) {
        DiscordJobHandler handler = handlers.get(job.kind());
        if (handler == null) {
            repository.failed(job.id(), "The bot no longer knows how to do this.");
            return;
        }
        try {
            DataObject result = handler.run(jda, job);
            repository.done(job.id(), result);
            log.debug("Discord job {} ({}) done after {} tries", job.id(), job.kind(), job.attempts());
        } catch (Throwable error) {
            int realTries = job.attempts() - job.waits();
            Next next = DiscordJobs.after(error, realTries, job.waits(), handler.idempotent(), Instant.now());
            switch (next) {
                case Retry retry -> {
                    if (!retry.rateLimit()) log.info("Discord job {} ({}) will be tried again: {}", job.id(), job.kind(), error.toString());
                    repository.retryAt(job.id(), retry.at(), retry.reason(), retry.rateLimit());
                    long ms = Math.max(0, Duration.between(Instant.now(), retry.at()).toMillis());
                    // wake up for it on time instead of on the next pass
                    lane.schedule((Runnable) this::drain, ms + 50, TimeUnit.MILLISECONDS);
                }
                case Fail fail -> {
                    log.info("Discord job {} ({}) failed: {} ({})", job.id(), job.kind(), fail.reason(), error.toString());
                    repository.failed(job.id(), fail.reason());
                }
            }
        }
    }
}
