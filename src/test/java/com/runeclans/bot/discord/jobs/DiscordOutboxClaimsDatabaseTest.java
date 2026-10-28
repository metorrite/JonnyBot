package com.runeclans.bot.discord.jobs;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.discord.jobs.DiscordJob.State;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two bot processes sharing the outbox (T-696, docs/SCALING-STATE.md M3, M4): a starting process leaves a live one's jobs alone, a gone one's
 * jobs are recovered once their claim expires, a late finish from a process whose claim was taken over changes nothing, and each process only
 * claims jobs for servers on its own shards. "Time passing" is a claim made older in the database, since expiry is judged by Postgres' clock.
 */
class DiscordOutboxClaimsDatabaseTest {
    private static final long GUILD = 990_000_000_000_000_696L;
    private static final long OTHER_GUILD = 990_000_000_000_000_697L;
    private static final long ACTOR = 990_000_000_000_001_696L;
    private static final String A = "test-process-a";
    private static final String B = "test-process-b";
    private static final List<String> IDEMPOTENT = List.of("test.nickname");
    private static final Duration EXPIRY = DiscordOutbox.CLAIM_EXPIRY;

    @Test
    void aStartingProcessLeavesALiveOnesJobsAloneAndRecoversAGoneOnesAfterExpiry() throws Exception {
        ConnectionSupplier connections = LocalDatabase.supplier();
        DiscordJobRepository repository = new DiscordJobRepository(connections);
        try {
            clean(connections);
            DiscordJob nick = repository.enqueue("test.nickname", GUILD, ACTOR, null, DataObject.empty());
            DiscordJob post = repository.enqueue("test.post", GUILD, ACTOR, null, DataObject.empty());
            assertEquals(2, ours(repository.claimDue(A, 50, null)).size());

            // B starts while A runs them: nothing of A's is touched
            assertEquals(0, repository.recover(B, IDEMPOTENT, EXPIRY));
            assertEquals(State.RUNNING, repository.get(nick.id()).orElseThrow().state());
            assertEquals(State.RUNNING, repository.get(post.id()).orElseThrow().state());

            // just inside the expiry: still A's
            age(connections, EXPIRY.minusSeconds(10));
            assertEquals(0, repository.recover(B, IDEMPOTENT, EXPIRY));

            // A's heartbeat keeps a claim fresh however long the job takes
            age(connections, EXPIRY.plusMinutes(1));
            assertEquals(2, repository.heartbeat(A));
            assertEquals(0, repository.recover(B, IDEMPOTENT, EXPIRY));

            // A process never recovers its own jobs, even with a stale claim: it is still running them
            age(connections, EXPIRY.plusMinutes(1));
            assertEquals(0, repository.recover(A, IDEMPOTENT, EXPIRY));

            // A stops refreshing (it is gone): B puts the nickname back and ends the post, as a restart did before
            assertEquals(2, repository.recover(B, IDEMPOTENT, EXPIRY));
            assertEquals(State.RETRYING, repository.get(nick.id()).orElseThrow().state());
            DiscordJob lost = repository.get(post.id()).orElseThrow();
            assertEquals(State.FAILED, lost.state());
            assertTrue(lost.error().contains("may or may not"));

            // B takes the nickname over; A waking up late and finishing changes nothing
            DiscordJob taken = ours(repository.claimDue(B, 50, null)).getFirst();
            assertEquals(nick.id(), taken.id());
            repository.done(A, nick.id(), DataObject.empty().put("by", "A"));
            repository.failed(A, nick.id(), "A's late failure");
            repository.retryAt(A, nick.id(), java.time.Instant.now(), "A's late retry", false);
            assertEquals(State.RUNNING, repository.get(nick.id()).orElseThrow().state(), "only the claim holder finishes a job");
            assertEquals(0, repository.heartbeat(A), "A holds nothing any more");
            repository.done(B, nick.id(), DataObject.empty().put("by", "B"));
            DiscordJob done = repository.get(nick.id()).orElseThrow();
            assertEquals(State.DONE, done.state());
            assertEquals("B", done.result().getString("by"));
        } finally {
            clean(connections);
        }
    }

    @Test
    void aGracefulStopEndsOnlyItsOwnJobsAtOnce() throws Exception {
        ConnectionSupplier connections = LocalDatabase.supplier();
        DiscordJobRepository repository = new DiscordJobRepository(connections);
        try {
            clean(connections);
            DiscordJob nick = repository.enqueue("test.nickname", GUILD, ACTOR, null, DataObject.empty());
            DiscordJob post = repository.enqueue("test.post", GUILD, ACTOR, null, DataObject.empty());
            assertEquals(2, ours(repository.claimDue(A, 50, null)).size());
            DiscordJob others = repository.enqueue("test.nickname", OTHER_GUILD, ACTOR, null, DataObject.empty());
            assertEquals(1, ours(repository.claimDue(B, 50, null)).size());

            assertEquals(2, repository.release(A, IDEMPOTENT));
            assertEquals(State.RETRYING, repository.get(nick.id()).orElseThrow().state());
            assertEquals(State.FAILED, repository.get(post.id()).orElseThrow().state());
            assertEquals(State.RUNNING, repository.get(others.id()).orElseThrow().state(), "B's job is B's");
            repository.done(A, post.id(), DataObject.empty());
            assertEquals(State.FAILED, repository.get(post.id()).orElseThrow().state(), "a job finishing after the release changes nothing");
        } finally {
            clean(connections);
        }
    }

    @Test
    void aJobTakenBeforeClaimsExistedIsRecoveredOnceItIsOld() throws Exception {
        ConnectionSupplier connections = LocalDatabase.supplier();
        DiscordJobRepository repository = new DiscordJobRepository(connections);
        try {
            clean(connections);
            DiscordJob nick = repository.enqueue("test.nickname", GUILD, ACTOR, null, DataObject.empty());
            // what the old code left behind: running, no claim
            exec(connections, "UPDATE younglings.discord_job SET state = 'running', updated_at = now() WHERE id = " + nick.id());
            assertEquals(0, repository.recover(B, IDEMPOTENT, EXPIRY), "an old-code process may still be running it");
            exec(connections, "UPDATE younglings.discord_job SET updated_at = now() - interval '3 minutes' WHERE id = " + nick.id());
            assertEquals(1, repository.recover(B, IDEMPOTENT, EXPIRY));
            assertEquals(State.RETRYING, repository.get(nick.id()).orElseThrow().state());
        } finally {
            clean(connections);
        }
    }

    @Test
    void eachProcessClaimsOnlyTheServersOnItsOwnShards() throws Exception {
        ConnectionSupplier connections = LocalDatabase.supplier();
        DiscordJobRepository repository = new DiscordJobRepository(connections);
        try {
            clean(connections);
            int shardOfGuild = Shards.shardOf(GUILD, 2);
            long sameShard = GUILD;
            long otherShard = OTHER_GUILD + (Shards.shardOf(OTHER_GUILD, 2) == shardOfGuild ? 1L << 22 : 0);
            assertNotEquals(shardOfGuild, Shards.shardOf(otherShard, 2));
            DiscordJob mine = repository.enqueue("test.nickname", sameShard, ACTOR, null, DataObject.empty());
            DiscordJob theirs = repository.enqueue("test.nickname", otherShard, ACTOR, null, DataObject.empty());

            List<DiscordJob> taken = ours(repository.claimDue(A, 50, new DiscordJobRepository.ShardSet(2, List.of(shardOfGuild))));
            assertEquals(List.of(mine.id()), taken.stream().map(DiscordJob::id).toList());
            assertEquals(State.QUEUED, repository.get(theirs.id()).orElseThrow().state(), "left for the process that runs that shard");

            taken = ours(repository.claimDue(B, 50, new DiscordJobRepository.ShardSet(2, List.of(1 - shardOfGuild))));
            assertEquals(List.of(theirs.id()), taken.stream().map(DiscordJob::id).toList());
        } finally {
            clean(connections, OTHER_GUILD + (1L << 22));
        }
    }

    @Test
    void twoProcessesClaimingAtOnceNeverTakeTheSameJob() throws Exception {
        ConnectionSupplier connections = LocalDatabase.supplier();
        DiscordJobRepository repository = new DiscordJobRepository(connections);
        try {
            clean(connections);
            java.util.Set<Long> queued = new java.util.HashSet<>();
            for (int i = 0; i < 40; i++) queued.add(repository.enqueue("test.nickname", GUILD, ACTOR, null, DataObject.empty()).id());
            java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.function.Function<String, java.util.concurrent.Callable<List<Long>>> drain = who -> () -> {
                    start.await();
                    List<Long> taken = new java.util.ArrayList<>();
                    while (true) {
                        List<DiscordJob> batch = ours(repository.claimDue(who, 3, null));
                        if (batch.isEmpty()) return taken;
                        for (DiscordJob job : batch) taken.add(job.id());
                    }
                };
                var a = pool.submit(drain.apply(A));
                var b = pool.submit(drain.apply(B));
                List<Long> byA = a.get(30, java.util.concurrent.TimeUnit.SECONDS);
                List<Long> byB = b.get(30, java.util.concurrent.TimeUnit.SECONDS);
                java.util.Set<Long> both = new java.util.HashSet<>(byA);
                both.retainAll(byB);
                assertTrue(both.isEmpty(), "no job taken twice: " + both);
                java.util.Set<Long> all = new java.util.HashSet<>(byA);
                all.addAll(byB);
                assertEquals(queued, all, "every job taken once");
            } finally {
                pool.shutdownNow();
            }
        } finally {
            clean(connections);
        }
    }

    /** Only the jobs of the test servers (the table may hold others). */
    private static List<DiscordJob> ours(List<DiscordJob> jobs) {
        return jobs.stream().filter(j -> j.guildId() == GUILD || j.guildId() == OTHER_GUILD || j.guildId() == OTHER_GUILD + (1L << 22)).toList();
    }

    private static void age(ConnectionSupplier connections, Duration age) throws Exception {
        try (Connection c = connections.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.discord_job SET claimed_at = now() - make_interval(secs => ?) WHERE guild_id = ?")) {
            s.setDouble(1, age.toSeconds());
            s.setLong(2, GUILD);
            s.executeUpdate();
        }
    }

    private static void exec(ConnectionSupplier connections, String sql) throws Exception {
        try (Connection c = connections.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.executeUpdate();
        }
    }

    private static void clean(ConnectionSupplier connections, long... more) throws Exception {
        try (Connection c = connections.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.discord_job WHERE guild_id = ANY (?)")) {
            Long[] ids = new Long[2 + more.length];
            ids[0] = GUILD;
            ids[1] = OTHER_GUILD;
            for (int i = 0; i < more.length; i++) ids[2 + i] = more[i];
            s.setArray(1, c.createArrayOf("bigint", ids));
            s.executeUpdate();
        }
    }
}
