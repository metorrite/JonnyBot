package com.younglings.bot.testsupport;

import com.younglings.bot.discord.jobs.DiscordJob;
import com.younglings.bot.discord.jobs.DiscordOutbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A stand-in Discord job outbox for route tests: every enqueue answers a queued job with id {@value #ID} and the kind and payload it was given. */
public final class QueuedJobs {
    public static final long ID = 77L;

    private QueuedJobs() {}

    public static DiscordOutbox outbox() {
        DiscordOutbox outbox = mock(DiscordOutbox.class);
        when(outbox.enqueue(anyString(), anyLong(), anyLong(), any(), any())).thenAnswer(a -> new DiscordJob(ID, a.getArgument(0), a.getArgument(1), a.getArgument(2),
                a.getArgument(3), a.getArgument(4), DiscordJob.State.QUEUED, 0, 0, null, null, null, null, null));
        return outbox;
    }
}
