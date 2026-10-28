package com.runeclans.bot.discord.jobs;

import com.runeclans.bot.discord.jobs.DiscordJob.State;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Discord job outbox against a real database (T-456): queueing, replacing, running, retrying, failing, and who may see a job. */
class DiscordOutboxDatabaseTest {
    private static final long GUILD = 990_000_000_000_000_456L;
    private static final long OTHER_GUILD = 990_000_000_000_000_457L;
    private static final long ACTOR = 990_000_000_000_001_456L;

    /** A handler the test steers: each run takes the next scripted outcome (none left = success). */
    private static final class Scripted implements DiscordJobHandler {
        final String kind;
        final boolean idempotent;
        final java.util.ArrayDeque<RuntimeException> script = new java.util.ArrayDeque<>();
        final AtomicInteger runs = new AtomicInteger();

        Scripted(String kind, boolean idempotent) {
            this.kind = kind;
            this.idempotent = idempotent;
        }

        @Override
        public String kind() {
            return kind;
        }

        @Override
        public boolean idempotent() {
            return idempotent;
        }

        @Override
        public DataObject run(JDA jda, DiscordJob job) {
            runs.incrementAndGet();
            RuntimeException next = script.poll();
            if (next != null) throw next;
            return DataObject.empty().put("ran", job.payload().getString("value", ""));
        }
    }

    @Test
    void jobsRunInTheBackgroundAndEndDoneRetryingOrFailed() throws Exception {
        ConnectionSupplier connections = LocalDatabase.supplier();
        DiscordJobRepository repository = new DiscordJobRepository(connections);
        Scripted nickname = new Scripted("test.nickname", true);
        Scripted post = new Scripted("test.post", false);
        DiscordOutbox outbox = new DiscordOutbox(repository, List.of(nickname, post));
        try {
            clean(connections);

            // queueing answers at once, and a second change of the same thing replaces the waiting one
            DiscordJob first = outbox.enqueue("test.nickname", GUILD, ACTOR, "nick:" + GUILD + ":" + ACTOR, DataObject.empty().put("value", "Old"));
            assertEquals(State.QUEUED, first.state());
            DiscordJob second = outbox.enqueue("test.nickname", GUILD, ACTOR, "nick:" + GUILD + ":" + ACTOR, DataObject.empty().put("value", "New"));
            assertEquals(first.id(), second.id(), "one waiting job per key");
            assertEquals("New", repository.get(first.id()).orElseThrow().payload().getString("value"));

            // a rate limit: retrying, not failed, and it doesn't count as a real try
            nickname.script.add(new DiscordJobs.RetryLater(Duration.ofSeconds(30), "Discord asked the bot to wait 30 s; it will try again by itself."));
            outbox.drain(null);
            DiscordJob waiting = repository.get(first.id()).orElseThrow();
            assertEquals(State.RETRYING, waiting.state());
            assertEquals(1, waiting.waits());
            assertTrue(waiting.error().contains("wait 30 s"));
            assertEquals("retrying", waiting.toJson().getString("state"));
            assertTrue(waiting.toJson().getString("nextAttemptAt", null) != null, "the website can say when");

            outbox.drain(null);
            assertEquals(1, nickname.runs.get(), "not run again before Discord allows it");

            makeDue(connections, first.id());
            outbox.drain(null);
            DiscordJob done = repository.get(first.id()).orElseThrow();
            assertEquals(State.DONE, done.state());
            assertEquals("New", done.result().getString("ran"));
            assertEquals(2, done.attempts());

            // a fault that may pass: tried again for an idempotent job ...
            DiscordJob flaky = outbox.enqueue("test.nickname", GUILD, ACTOR, null, DataObject.empty().put("value", "x"));
            nickname.script.add(new IllegalStateException("connection reset"));
            outbox.drain(null);
            assertEquals(State.RETRYING, repository.get(flaky.id()).orElseThrow().state());
            // ... but a post that might have gone out is never sent twice by itself
            DiscordJob posted = outbox.enqueue("test.post", GUILD, ACTOR, null, DataObject.empty().put("value", "hello"));
            post.script.add(new IllegalStateException("connection reset"));
            outbox.drain(null);
            DiscordJob unsure = repository.get(posted.id()).orElseThrow();
            assertEquals(State.FAILED, unsure.state());
            assertTrue(unsure.error().contains("may or may not"));

            // Discord's plain "no" ends a job at once, with the reason in words
            DiscordJob refused = outbox.enqueue("test.post", GUILD, ACTOR, null, DataObject.empty().put("value", "x"));
            post.script.add(new DiscordJobs.Refused("That channel no longer exists."));
            outbox.drain(null);
            assertEquals("That channel no longer exists.", repository.get(refused.id()).orElseThrow().error());

            // the website's Retry sends a failed job again, once, and only for its own person and server
            assertFalse(outbox.retry(refused.id(), OTHER_GUILD, ACTOR));
            assertFalse(outbox.retry(refused.id(), GUILD, ACTOR + 1));
            assertTrue(outbox.retry(refused.id(), GUILD, ACTOR));
            assertFalse(outbox.retry(refused.id(), GUILD, ACTOR), "it isn't failed any more");
            outbox.drain(null);
            assertEquals(State.DONE, repository.get(refused.id()).orElseThrow().state());

            // a job is only shown to the person who asked, in that server
            assertEquals(1, outbox.find(List.of(refused.id()), GUILD, ACTOR).size());
            assertEquals(0, outbox.find(List.of(refused.id()), OTHER_GUILD, ACTOR).size());
            assertEquals(0, outbox.find(List.of(refused.id()), GUILD, ACTOR + 1).size());
            assertEquals(1, outbox.find(List.of(refused.id()), null, ACTOR).size(), "a clan site asks without the server");
            assertEquals(0, outbox.find(List.of(refused.id()), null, ACTOR + 1).size());
            assertFalse(outbox.find(List.of(refused.id()), GUILD, ACTOR).getFirst().toJson().hasKey("payload"), "never the payload");
        } finally {
            clean(connections);
        }
    }

    @Test
    void aChangeQueuedWhileTheOldOneRunsWinsAndARestartNeverRepostsByItself() throws Exception {
        ConnectionSupplier connections = LocalDatabase.supplier();
        DiscordJobRepository repository = new DiscordJobRepository(connections);
        try {
            clean(connections);
            String key = "nick:" + GUILD + ":" + ACTOR + ":race";
            DiscordJob running = repository.enqueue("test.nickname", GUILD, ACTOR, key, DataObject.empty().put("value", "A"));
            assertEquals(running.id(), repository.claimDue(10).stream().filter(j -> j.guildId() == GUILD).findFirst().orElseThrow().id());
            DiscordJob newer = repository.enqueue("test.nickname", GUILD, ACTOR, key, DataObject.empty().put("value", "B"));
            assertNotEquals(running.id(), newer.id(), "a running job is never changed under it");
            repository.retryAt(running.id(), java.time.Instant.now(), "rate limited", true);
            assertEquals(State.DONE, repository.get(running.id()).orElseThrow().state(), "the newer change already says how it should end");
            assertEquals(State.QUEUED, repository.get(newer.id()).orElseThrow().state());

            // after a restart: an idempotent job is tried again, a post ends as failed (it may already be in the channel)
            DiscordJob nick = repository.enqueue("test.nickname", GUILD, ACTOR, null, DataObject.empty());
            DiscordJob post = repository.enqueue("test.post", GUILD, ACTOR, null, DataObject.empty());
            repository.claimDue(50);
            repository.recover(List.of("test.nickname"));
            assertEquals(State.RETRYING, repository.get(nick.id()).orElseThrow().state());
            DiscordJob lost = repository.get(post.id()).orElseThrow();
            assertEquals(State.FAILED, lost.state());
            assertTrue(lost.error().contains("restarted"));
        } finally {
            clean(connections);
        }
    }

    private static void makeDue(ConnectionSupplier connections, long id) throws Exception {
        try (Connection c = connections.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET next_attempt_at = now() - interval '1 second' WHERE id = ?")) {
            s.setLong(1, id);
            s.executeUpdate();
        }
    }

    private static void clean(ConnectionSupplier connections) throws Exception {
        try (Connection c = connections.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.discord_job WHERE guild_id IN (?, ?)")) {
            s.setLong(1, GUILD);
            s.setLong(2, OTHER_GUILD);
            s.executeUpdate();
        }
    }
}
