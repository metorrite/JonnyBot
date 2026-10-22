package com.younglings.bot.commands.ticket;

import com.younglings.bot.alerts.AutoPostRouter;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The ticket system's background jobs. They keep no schedule of their own in memory: every minute they ask the
 * database what's due (a ticket nobody joined in time, a closed ticket whose channel is still there), so a
 * restart simply picks up where things stood — a ticket that came due while the bot was down is handled as
 * soon as it's back.
 */
@BService
public class TicketScheduler {
    private static final Logger log = LoggerFactory.getLogger(TicketScheduler.class);
    private static final int TICKS_PER_PURGE = 60; // transcript retention only needs checking about hourly
    private static final int TICKS_PER_INACTIVITY_CHECK = 10; // quiet tickets only need looking at every ten minutes

    private final TicketService ticketService;
    private final ScheduledExecutorService executor = BotScheduler.lane("ticket-scheduler");
    private int ticks;

    public TicketScheduler(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        log.info("Ticket scheduler starting: checking escalations and closed tickets every minute.");
        executor.scheduleWithFixedDelay(() -> tick(jda), 20, 60, TimeUnit.SECONDS);
    }

    private void tick(JDA jda) {
        if (AutoPostRouter.diverting()) return; // escalations ping roles and closing posts in real tickets
        try {
            ticketService.escalateDue(jda);
            ticketService.sweepClosed(jda);
            if (ticks % TICKS_PER_INACTIVITY_CHECK == 0) ticketService.closeInactive(jda);
            if (ticks++ % TICKS_PER_PURGE == 0) ticketService.purgeOldTranscripts(jda);
        } catch (Exception e) {
            log.error("Ticket background check failed", e); // never let one bad run stop the schedule
        }
    }
}
