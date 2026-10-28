package com.runeclans.bot.internal;

import com.runeclans.bot.points.PointsRepository;
import com.runeclans.bot.points.PointsRepository.Submission;
import com.runeclans.bot.points.PointsRules;
import com.runeclans.bot.points.PointsService;
import com.runeclans.bot.points.RewardsRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The submissions page re-reads proof links from the review posts: all asked for at once, and never waited on past a deadline. */
class PointsSubmissionsAdminApiTest {
    private static final long GUILD = 5L;
    private static final long REVIEW = 9L;
    private static final long GONE = 10L;

    private PointsRepository repository;
    private PointsAdminApi api;
    private Guild guild;
    private TextChannel channel;

    @BeforeEach
    void setUp() {
        repository = mock(PointsRepository.class);
        api = new PointsAdminApi(repository, mock(PointsService.class), mock(RewardsRepository.class));
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        channel = mock(TextChannel.class);
        when(guild.getTextChannelById(REVIEW)).thenReturn(channel);
        when(repository.decided(GUILD, 25)).thenReturn(List.of());
    }

    private static Submission submission(long id, Long channelId, Long messageId, PointsRules.Status status) {
        return new Submission(id, GUILD, 100 + id, "did a thing", "proof.png", "https://stored.example/" + id, channelId, messageId, status,
                null, null, null, Instant.EPOCH, status == PointsRules.Status.PENDING ? null : Instant.EPOCH);
    }

    private static Message withAttachment(String url) {
        Message message = mock(Message.class);
        if (url == null) {
            when(message.getAttachments()).thenReturn(List.of());
        } else {
            Message.Attachment attachment = mock(Message.Attachment.class);
            when(attachment.getUrl()).thenReturn(url);
            when(message.getAttachments()).thenReturn(List.of(attachment));
        }
        return message;
    }

    @SuppressWarnings("unchecked")
    private static RestAction<Message> answering(CompletableFuture<Message> future) {
        RestAction<Message> action = mock(RestAction.class);
        when(action.submit()).thenReturn(future);
        when(action.complete()).thenAnswer(i -> future.join()); // what the one-at-a-time version used: lets this test run against it too
        return action;
    }

    private static List<String> proofs(DataObject answer, String list) {
        DataArray rows = answer.getArray(list);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) out.add(rows.getObject(i).getString("proofUrl", null));
        return out;
    }

    @Test
    void eachRowGetsTheSameLinkAsBefore() {
        // 1: fresh attachment; 2: post deleted; 3: post with no attachment; 4: no review post; 5: channel gone; 6: can't read the channel
        when(repository.pending(GUILD, 25)).thenReturn(List.of(
                submission(1, REVIEW, 11L, PointsRules.Status.PENDING),
                submission(2, REVIEW, 12L, PointsRules.Status.PENDING),
                submission(3, REVIEW, 13L, PointsRules.Status.PENDING),
                submission(4, null, null, PointsRules.Status.PENDING),
                submission(5, GONE, 15L, PointsRules.Status.PENDING),
                submission(6, REVIEW, 16L, PointsRules.Status.PENDING)));
        when(repository.decided(GUILD, 25)).thenReturn(List.of(submission(7, REVIEW, 17L, PointsRules.Status.APPROVED)));
        RestAction<Message> fresh = answering(CompletableFuture.completedFuture(withAttachment("https://fresh.example/1")));
        RestAction<Message> deleted = answering(CompletableFuture.failedFuture(new IllegalStateException("Unknown Message")));
        RestAction<Message> bare = answering(CompletableFuture.completedFuture(withAttachment(null)));
        InsufficientPermissionException noAccess = mock(InsufficientPermissionException.class);
        when(channel.retrieveMessageById(11L)).thenReturn(fresh);
        when(channel.retrieveMessageById(12L)).thenReturn(deleted);
        when(channel.retrieveMessageById(13L)).thenReturn(bare);
        when(channel.retrieveMessageById(16L)).thenThrow(noAccess);

        DataObject answer = api.submissions(guild);

        assertEquals(List.of("https://fresh.example/1", "https://stored.example/2", "https://stored.example/3", "https://stored.example/4",
                "https://stored.example/5", "https://stored.example/6"), proofs(answer, "pending"));
        assertEquals(List.of("https://stored.example/7"), proofs(answer, "decided"), "decided rows keep the stored link and ask Discord nothing");
        verify(channel, never()).retrieveMessageById(17L);
        assertEquals("1", answer.getArray("pending").getObject(0).getString("id"));
        assertEquals("PENDING", answer.getArray("pending").getObject(0).getString("status"));
    }

    @Test
    void everyPostIsAskedForBeforeAnyIsWaitedOn() {
        List<Submission> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) rows.add(submission(i, REVIEW, 1000L + i, PointsRules.Status.PENDING));
        when(repository.pending(GUILD, 25)).thenReturn(rows);
        Message message = withAttachment("https://fresh.example/x");
        AtomicInteger asked = new AtomicInteger();
        when(channel.retrieveMessageById(anyLong())).thenAnswer(i -> {
            asked.incrementAndGet();
            return answering(CompletableFuture.supplyAsync(() -> message, CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS)));
        });

        long started = System.nanoTime();
        DataObject answer = api.submissions(guild);
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(25, asked.get());
        assertEquals(25, proofs(answer, "pending").stream().filter("https://fresh.example/x"::equals).count());
        assertTrue(tookMs < 2_000, "25 posts at 200 ms each are waited on together, not one after another (took " + tookMs + " ms)");
    }

    @Test
    void aPostDiscordSitsOnFallsBackToTheStoredLinkAtTheDeadlineAndIsCancelled() {
        List<Submission> rows = List.of(submission(1, REVIEW, 11L, PointsRules.Status.PENDING), submission(2, REVIEW, 12L, PointsRules.Status.PENDING));
        CompletableFuture<Message> hanging = new CompletableFuture<>();
        RestAction<Message> fresh = answering(CompletableFuture.completedFuture(withAttachment("https://fresh.example/1")));
        RestAction<Message> late = answering(hanging);
        when(channel.retrieveMessageById(11L)).thenReturn(fresh);
        when(channel.retrieveMessageById(12L)).thenReturn(late);

        long started = System.nanoTime();
        List<String> proofs = PointsAdminApi.freshProofs(guild, rows, Duration.ofMillis(100));
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(List.of("https://fresh.example/1", "https://stored.example/2"), proofs);
        assertTrue(hanging.isCancelled(), "the late lookup is called off");
        assertTrue(tookMs < 1_000, "gave up at the deadline (took " + tookMs + " ms)");
        assertTrue(PointsAdminApi.PROOF_BUDGET.toMillis() < 8_000, "inside the website's patience");
    }
}
