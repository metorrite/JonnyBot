package com.runeclans.bot.commandusage;

import com.runeclans.bot.BotListener;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Counts every slash command used in a server (top-level name only, never who ran it) for the dashboard's Commands page (T-191). Uses are counted
 * in memory and saved once a minute and when the bot shuts down, so a busy server costs one small write a minute; at most a minute of counts is
 * lost if the bot crashes. Old days are pruned once a day. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class CommandUsageListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(CommandUsageListener.class);

    private final CommandUsageRepository repository;
    private final CommandUsageCounter counter = new CommandUsageCounter();
    private final AtomicBoolean started = new AtomicBoolean();
    private final ScheduledExecutorService executor = BotScheduler.lane("command-usage");

    public CommandUsageListener(CommandUsageRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild() || event.getGuild() == null) return;
        counter.record(event.getGuild().getIdLong(), event.getName(), LocalDate.now(ZoneOffset.UTC));
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!started.compareAndSet(false, true)) return;
        executor.scheduleWithFixedDelay(this::flush, 1, 1, TimeUnit.MINUTES);
        executor.scheduleWithFixedDelay(this::prune, 5, 24 * 60, TimeUnit.MINUTES);
    }

    @Override
    public void onShutdown(ShutdownEvent event) {
        flush();
    }

    /** Saves what has been counted; on failure the counts go back into memory for the next try. */
    void flush() {
        if (counter.isEmpty()) return;
        Map<CommandUsageCounter.Key, Integer> batch = counter.drain();
        try {
            repository.add(batch);
        } catch (SQLException | RuntimeException e) {
            counter.restore(batch);
            log.warn("Could not save {} command usage counts; keeping them for the next try", batch.size(), e);
        }
    }

    private void prune() {
        int gone = repository.pruneBefore(LocalDate.now(ZoneOffset.UTC).minusDays(CommandUsageRepository.KEEP_DAYS));
        if (gone > 0) log.info("Pruned {} command usage rows older than {} days", gone, CommandUsageRepository.KEEP_DAYS);
    }
}
