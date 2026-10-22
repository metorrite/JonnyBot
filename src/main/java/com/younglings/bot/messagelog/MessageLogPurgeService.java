package com.younglings.bot.messagelog;

import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Removes remembered message text that is past its server's retention, and all of it for servers that switched the feature off. Runs a few minutes after start
 * and then every six hours (so text never outlives its limit by more than that, and a restart can't skip a night).
 */
@BService
public class MessageLogPurgeService {
    private static final Logger log = LoggerFactory.getLogger(MessageLogPurgeService.class);

    private final MessageLogRepository repository;
    private final ScheduledExecutorService executor = BotScheduler.lane("message-log-purge");

    public MessageLogPurgeService(MessageLogRepository repository) {
        this.repository = repository;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        executor.scheduleWithFixedDelay(this::sweep, 5, 6 * 60, TimeUnit.MINUTES);
    }

    void sweep() {
        try {
            int removed = repository.purge();
            if (removed > 0) log.info("Message log purge removed {} remembered message(s).", removed);
        } catch (Exception e) {
            log.error("The message log purge failed", e);
        }
    }
}
