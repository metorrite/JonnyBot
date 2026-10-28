package com.runeclans.bot.devtools;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Keeps the bot's recent warnings and errors in memory, so a snapshot can include what went wrong just before it was taken without
 * anyone having to find the right place in the host's log. Only WARN and above, only the last half hour, at most {@link #CAPACITY}
 * entries; nothing is written anywhere. Text goes through {@link SnapshotRedactor} when a snapshot is made, not here.
 */
@BService
public class SnapshotLogBuffer {
    static final int CAPACITY = 400;
    static final Duration WINDOW = Duration.ofMinutes(30);
    private static final int MAX_LINE = 4_000;
    private static final int MAX_STACK_LINES = 12;

    public record Entry(Instant at, String level, String logger, String message) {
        @Override
        public String toString() {
            return at + " " + level + " " + logger + " - " + message;
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    /** Replaceable so tests can move time without waiting. */
    LongSupplier clock = System::currentTimeMillis;

    public SnapshotLogBuffer() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
                @Override
                protected void append(ILoggingEvent event) {
                    record(event);
                }
            };
            appender.setContext(context);
            appender.setName("dev-snapshot-buffer");
            appender.start();
            context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        }
    }

    void record(ILoggingEvent event) {
        if (!event.getLevel().isGreaterOrEqual(Level.WARN)) return;
        StringBuilder message = new StringBuilder(event.getFormattedMessage());
        IThrowableProxy error = event.getThrowableProxy();
        if (error != null) {
            message.append("\n  ").append(error.getClassName()).append(": ").append(error.getMessage());
            var frames = error.getStackTraceElementProxyArray();
            for (int i = 0; frames != null && i < Math.min(frames.length, MAX_STACK_LINES); i++) message.append("\n    at ").append(frames[i].getSTEAsString());
        }
        add(new Entry(Instant.ofEpochMilli(event.getTimeStamp()), event.getLevel().toString(), event.getLoggerName(),
                message.length() > MAX_LINE ? message.substring(0, MAX_LINE) + "..." : message.toString()));
    }

    void add(Entry entry) {
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > CAPACITY) entries.removeFirst();
        }
    }

    /** The entries from the last half hour, oldest first. */
    public List<Entry> recent() {
        Instant cutoff = Instant.ofEpochMilli(clock.getAsLong()).minus(WINDOW);
        synchronized (entries) {
            List<Entry> out = new ArrayList<>();
            for (Entry entry : entries) if (!entry.at().isBefore(cutoff)) out.add(entry);
            return out;
        }
    }
}
