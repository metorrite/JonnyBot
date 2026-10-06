package com.younglings.bot.internal;

import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Posts the messages staff scheduled from the dashboard, within about half a minute of their time. */
@BService
public class ScheduledPostRunner {
    private static final Logger log = LoggerFactory.getLogger(ScheduledPostRunner.class);

    private final AdminOpsApi ops;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor((ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "scheduled-posts");
        thread.setDaemon(true);
        return thread;
    });

    public ScheduledPostRunner(AdminOpsApi ops) {
        this.ops = ops;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> {
            try {
                ops.runDue(jda);
            } catch (RuntimeException e) {
                log.warn("Scheduled-post pass failed", e); // the next pass tries again
            }
        }, 45, 30, TimeUnit.SECONDS);
    }
}
