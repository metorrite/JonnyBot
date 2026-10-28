package com.younglings.bot.discord.jobs;

import com.younglings.bot.discord.jobs.DiscordJobs.Fail;
import com.younglings.bot.discord.jobs.DiscordJobs.Next;
import com.younglings.bot.discord.jobs.DiscordJobs.Retry;
import com.younglings.bot.discord.Shards;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.exceptions.RateLimitedException;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscordJobsTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    void aRateLimitIsAWaitNotAnError() {
        Next next = DiscordJobs.after(new DiscordJobs.RetryLater(Duration.ofSeconds(12), "wait"), 1, 0, false, NOW);
        Retry retry = assertInstanceOf(Retry.class, next, "even a post is tried again: nothing was sent");
        assertTrue(retry.rateLimit());
        assertEquals(NOW.plusSeconds(12), retry.at());

        Retry fromJda = assertInstanceOf(Retry.class, DiscordJobs.after(new RateLimitedException("route", 2500), 1, 0, true, NOW));
        assertEquals(NOW.plusMillis(2500), fromJda.at());
        assertTrue(fromJda.reason().contains("3 s"), fromJda.reason());

        Retry tiny = assertInstanceOf(Retry.class, DiscordJobs.after(new DiscordJobs.RetryLater(Duration.ofMillis(1), "wait"), 1, 0, true, NOW));
        assertEquals(NOW.plus(DiscordJobs.MIN_RATE_LIMIT_WAIT), tiny.at(), "never a busy loop");

        assertInstanceOf(Fail.class, DiscordJobs.after(new DiscordJobs.RetryLater(Duration.ofSeconds(1), "wait"), 1, DiscordJobs.MAX_RATE_LIMIT_WAITS, true, NOW),
                "a job that only ever waits gives up in the end");
    }

    @Test
    void aFaultThatMayPassIsTriedAgainWithGrowingWaitsOnlyWhenThatIsHarmless() {
        Retry first = assertInstanceOf(Retry.class, DiscordJobs.after(new IOException("reset"), 1, 0, true, NOW));
        assertFalse(first.rateLimit());
        assertEquals(NOW.plusSeconds(2), first.at());
        assertEquals(NOW.plusSeconds(8), assertInstanceOf(Retry.class, DiscordJobs.after(new IOException("reset"), 3, 0, true, NOW)).at());
        assertEquals(Duration.ofMinutes(5), DiscordJobs.backoff(30), "capped");
        assertInstanceOf(Fail.class, DiscordJobs.after(new IOException("reset"), DiscordJobs.MAX_ATTEMPTS, 0, true, NOW), "gives up after the last try");

        Fail post = assertInstanceOf(Fail.class, DiscordJobs.after(new IOException("reset"), 1, 0, false, NOW));
        assertTrue(post.reason().contains("may or may not"), "a post is never sent twice by itself: " + post.reason());
    }

    @Test
    void aRefusalEndsTheJobWithItsReasonAndWrappedErrorsAreSeenThrough() {
        Fail refused = assertInstanceOf(Fail.class, DiscordJobs.after(new CompletionException(new DiscordJobs.Refused("That role no longer exists.")), 1, 0, true, NOW));
        assertEquals("That role no longer exists.", refused.reason());
    }

    /** T-668: a server on another shard that is in an outage (or whose shard isn't connected yet) waits; it is not "left". */
    @Test
    void aServerOnAnotherShardInAnOutageWaitsInsteadOfBeingRefused() {
        long guildId = Long.parseLong("1".repeat(18));
        DiscordJob job = new DiscordJob(5L, "member.nickname", guildId, Long.parseLong("2".repeat(18)), null, DataObject.empty(), DiscordJob.State.RUNNING, 1, 0,
                null, null, null, null, null);
        ShardManager manager = mock(ShardManager.class);
        JDA local = mock(JDA.class);
        JDA holder = mock(JDA.class);
        when(local.getShardManager()).thenReturn(manager);
        when(manager.getShardsTotal()).thenReturn(2);
        int holderId = Shards.shardOf(guildId, 2);
        when(manager.getShardById(holderId)).thenReturn(holder);
        when(manager.getShardById(1 - holderId)).thenReturn(local);
        when(local.isUnavailable(guildId)).thenReturn(false); // the local shard knows nothing of it
        when(holder.isUnavailable(guildId)).thenReturn(true);
        assertThrows(DiscordJobs.RetryLater.class, () -> DiscordJobs.guild(local, job));

        when(manager.getShardById(holderId)).thenReturn(null); // that shard isn't running yet
        assertThrows(DiscordJobs.RetryLater.class, () -> DiscordJobs.guild(local, job));

        when(manager.getShardById(holderId)).thenReturn(holder);
        when(holder.isUnavailable(guildId)).thenReturn(false);
        assertThrows(DiscordJobs.Refused.class, () -> DiscordJobs.guild(local, job), "a server no shard holds is really gone");
    }

    @Test
    void theStatesReadAsTheWebsiteExpects() {
        assertEquals("retrying", DiscordJob.State.RETRYING.key());
        assertEquals(DiscordJob.State.FAILED, DiscordJob.State.ofKey("failed").orElseThrow());
        assertTrue(DiscordJob.State.DONE.finished());
        assertFalse(DiscordJob.State.RETRYING.finished());
    }
}
