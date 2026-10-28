package com.runeclans.bot.discord.jobs;

import io.github.freya022.botcommands.api.core.service.annotations.InterfacedService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.List;

/**
 * Does one kind of {@link DiscordJob} (T-456). Runs on the outbox's lane, never on a website request, so it may wait for Discord. Send each
 * Discord call through {@link DiscordJobs#send}, which never queues behind a rate limit: it hands the wait back to the outbox, which shows the
 * job as "retrying" and tries again when Discord allows.
 */
@InterfacedService(acceptMultiple = true)
public interface DiscordJobHandler {
    /** The job kind this handles, e.g. {@code member.nickname} (the first of {@link #kinds()} for a handler of several). */
    String kind();

    /** Every job kind this handles: one feature's handler may do several, e.g. post, edit and delete a message. */
    default List<String> kinds() {
        return List.of(kind());
    }

    /**
     * Whether running it twice is harmless (it sets a state rather than adding something). Only such jobs are tried again after the bot
     * restarted mid-way, or after an error that leaves it unclear whether Discord did it.
     */
    boolean idempotent();

    /** {@link #idempotent()} for one of {@link #kinds()}: a handler of several says per kind (posting isn't, deleting is). */
    default boolean idempotent(String kind) {
        return idempotent();
    }

    /**
     * Does the job. Answers what the website may want to know (or null). Throws {@link DiscordJobs.Refused} for a reason that trying again
     * won't fix, {@link DiscordJobs.RetryLater} to wait, anything else for a fault that may pass.
     */
    DataObject run(JDA jda, DiscordJob job) throws Exception;
}
