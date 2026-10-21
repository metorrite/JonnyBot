package com.younglings.bot.reminder;

import com.younglings.bot.alerts.AutoPostRouter;
import com.younglings.bot.reminder.ReminderRepository.Reminder;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.EnumSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Delivers reminders when they fall due: by DM, and, if the person's DMs are closed, in the channel they asked from (mentioning only
 * them). A reminder is removed before it is sent, so a slow send can never make it arrive twice; the cost is that one lost to a crash
 * in that instant is simply gone, which is the better failure for a reminder.
 */
@BService
public class ReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderRepository repository;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor((ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "reminders");
        thread.setDaemon(true);
        return thread;
    });

    public ReminderScheduler(ReminderRepository repository) {
        this.repository = repository;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> sweep(jda), 30, 15, TimeUnit.SECONDS);
    }

    /** What the reminder says when it arrives. */
    public static String message(Reminder reminder, boolean inChannel) {
        String mention = inChannel ? "<@" + reminder.userId() + "> " : "";
        return mention + "⏰ **Reminder:** " + reminder.text();
    }

    void sweep(JDA jda) {
        try {
            for (Reminder reminder : repository.due(Instant.now(), 50)) {
                if (!repository.delete(reminder.id(), reminder.userId())) continue; // cancelled (or delivered) a moment ago
                deliver(jda, reminder);
            }
        } catch (Exception e) {
            log.error("Reminder sweep failed", e);
        }
    }

    private void deliver(JDA jda, Reminder reminder) {
        if (AutoPostRouter.skip("a personal reminder")) return;
        jda.retrieveUserById(reminder.userId()).queue(user -> user.openPrivateChannel()
                        .flatMap(dm -> dm.sendMessage(message(reminder, false)))
                        .queue(sent -> {}, error -> fallback(jda, reminder)),
                error -> log.info("Reminder {}: its person is gone", reminder.id()));
    }

    private void fallback(JDA jda, Reminder reminder) {
        GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, reminder.channelId());
        if (channel == null || !channel.canTalk()) {
            log.info("Reminder {} couldn't be delivered: DMs are closed and the channel is gone", reminder.id());
            return;
        }
        channel.sendMessage(message(reminder, true)).setAllowedMentions(EnumSet.of(net.dv8tion.jda.api.entities.Message.MentionType.USER))
                .mentionUsers(reminder.userId()).queue(sent -> {}, error -> log.info("Reminder {} couldn't be posted in its channel", reminder.id()));
    }
}
