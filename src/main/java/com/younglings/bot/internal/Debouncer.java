package com.younglings.bot.internal;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Collapses a burst of "please refresh X" requests into one refresh. The first request for a key schedules the
 * work after a short delay; any request for the same key that arrives while it's waiting is dropped, because
 * the work reads the latest state when it finally runs anyway.
 * <p>
 * This is what keeps a flurry of votes or signup clicks from becoming a flurry of Discord message edits: Discord
 * rate-limits edits to a message, so queuing one per click only builds a backlog. Fifty clicks in a second now
 * cost one edit.
 */
@BService
public class Debouncer {
    private static final Logger log = LoggerFactory.getLogger(Debouncer.class);

    private final ConcurrentHashMap<String, Boolean> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor((ThreadFactory) r -> {
        Thread t = new Thread(r, "debouncer");
        t.setDaemon(true);
        return t;
    });

    /** Runs {@code work} once, {@code delayMillis} from now, unless a run for {@code key} is already waiting. */
    public void run(String key, long delayMillis, Runnable work) {
        if (pending.putIfAbsent(key, Boolean.TRUE) != null) return;
        executor.schedule(() -> {
            pending.remove(key); // clear first, so a request arriving during the work schedules the next run
            try {
                work.run();
            } catch (Exception e) {
                log.error("Debounced task '{}' failed", key, e);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }
}
