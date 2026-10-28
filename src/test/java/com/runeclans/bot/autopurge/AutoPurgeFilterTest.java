package com.runeclans.bot.autopurge;

import com.runeclans.bot.autopurge.AutoPurgeRule.Authors;
import com.runeclans.bot.autopurge.AutoPurgeRule.MessageFacts;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageHistory;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.MessageHistory.MessageRetrieveAction;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The auto-clean filters. A filter can only take messages OUT of what is deleted, never add to it; with no filter the rule is exactly what it
 * always was. Everything here is a mock or plain data: no real message is deleted, and the dry run is shown not to delete.
 */
class AutoPurgeFilterTest {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofDays(2));
    private static final Instant NEW = NOW.minus(Duration.ofMinutes(10));

    private static AutoPurgeRule rule(Authors authors, boolean attachments, String text, boolean keepPinned) {
        return new AutoPurgeRule(1, 2, 24 * 60, keepPinned, authors, attachments, text);
    }

    private static MessageFacts msg(Instant at, boolean byBot, int attachments, String text) {
        return new MessageFacts(at, false, byBot, attachments, text);
    }

    @Test
    void aRuleWithNoFilterDeletesEveryOldMessageExactlyAsBefore() {
        AutoPurgeRule plain = new AutoPurgeRule(1, 2, 24 * 60, true);
        assertFalse(plain.filtered());
        assertTrue(plain.shouldDelete(msg(OLD, false, 0, "hello"), NOW));
        assertTrue(plain.shouldDelete(msg(OLD, true, 3, ""), NOW));
        assertFalse(plain.shouldDelete(msg(NEW, false, 0, "hello"), NOW), "too new");
        assertEquals(AutoPurgeRule.Authors.ALL, plain.authors());
    }

    @Test
    void onlyBotsKeepsEveryPersonsMessage() {
        AutoPurgeRule bots = rule(Authors.ONLY_BOTS, false, null, true);
        assertTrue(bots.shouldDelete(msg(OLD, true, 0, "x"), NOW));
        assertFalse(bots.shouldDelete(msg(OLD, false, 0, "x"), NOW));
    }

    @Test
    void onlyPeopleKeepsEveryBotsMessage() {
        AutoPurgeRule people = rule(Authors.ONLY_HUMANS, false, null, true);
        assertTrue(people.shouldDelete(msg(OLD, false, 0, "x"), NOW));
        assertFalse(people.shouldDelete(msg(OLD, true, 0, "x"), NOW));
    }

    @Test
    void onlyWithAttachmentsKeepsPlainText() {
        AutoPurgeRule files = rule(Authors.ALL, true, null, true);
        assertTrue(files.shouldDelete(msg(OLD, false, 1, ""), NOW));
        assertFalse(files.shouldDelete(msg(OLD, false, 0, "a long message"), NOW));
    }

    @Test
    void theTextFilterIsAnyCaseSubstringAndKeepsEverythingElse() {
        AutoPurgeRule text = rule(Authors.ALL, false, "Free Nitro", true);
        assertTrue(text.shouldDelete(msg(OLD, false, 0, "get FREE NITRO here"), NOW));
        assertFalse(text.shouldDelete(msg(OLD, false, 0, "just chatting"), NOW));
        assertFalse(text.shouldDelete(msg(OLD, false, 1, null), NOW), "no text at all never matches a text filter");
    }

    @Test
    void severalFiltersAllHaveToMatch() {
        AutoPurgeRule all = rule(Authors.ONLY_BOTS, true, "report", true);
        assertTrue(all.shouldDelete(msg(OLD, true, 1, "Weekly report"), NOW));
        assertFalse(all.shouldDelete(msg(OLD, false, 1, "Weekly report"), NOW), "a person");
        assertFalse(all.shouldDelete(msg(OLD, true, 0, "Weekly report"), NOW), "no attachment");
        assertFalse(all.shouldDelete(msg(OLD, true, 1, "something else"), NOW), "other text");
    }

    @Test
    void aFilterNeverMakesAYoungOrPinnedMessageDue() {
        AutoPurgeRule bots = rule(Authors.ONLY_BOTS, false, null, true);
        assertFalse(bots.shouldDelete(msg(NEW, true, 0, "x"), NOW));
        assertFalse(bots.shouldDelete(new MessageFacts(OLD, true, true, 0, "x"), NOW), "pins are kept as before");
        assertTrue(rule(Authors.ONLY_BOTS, false, null, false).shouldDelete(new MessageFacts(OLD, true, true, 0, "x"), NOW), "unless the rule deletes pins too");
        assertFalse(bots.shouldDelete(msg(NOW.minus(Duration.ofDays(15)), true, 0, "x"), NOW), "too old for Discord to bulk delete");
    }

    @Test
    void blankTextCountsAsNoFilterAndLongTextIsRefused() {
        assertFalse(rule(Authors.ALL, false, "   ", true).filtered());
        assertEquals("abc", rule(Authors.ALL, false, "  abc ", true).containsText());
        assertTrue(AutoPurgeRule.textProblem("x".repeat(101)) != null);
        assertEquals(null, AutoPurgeRule.textProblem("x".repeat(100)));
    }

    // ---- the sweep and the dry run, against mock messages ----

    private static Message message(long id, Instant at, boolean bot, int attachments, String text) {
        Message m = mock(Message.class);
        User author = mock(User.class);
        when(author.isBot()).thenReturn(bot);
        when(m.getAuthor()).thenReturn(author);
        when(m.getIdLong()).thenReturn(id);
        when(m.getTimeCreated()).thenReturn(OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
        when(m.isPinned()).thenReturn(false);
        when(m.isWebhookMessage()).thenReturn(false);
        when(m.getAttachments()).thenReturn(attachments == 0 ? List.of() : List.of(mock(Message.Attachment.class)));
        when(m.getContentRaw()).thenReturn(text);
        return m;
    }

    private static GuildMessageChannel channelWith(List<Message> messages) {
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        MessageRetrieveAction action = mock(MessageRetrieveAction.class);
        MessageHistory history = mock(MessageHistory.class);
        when(history.getRetrievedHistory()).thenReturn(messages);
        when(action.complete()).thenReturn(history);
        when(channel.getHistoryBefore(anyLong(), anyInt())).thenReturn(action);
        when(channel.purgeMessages(anyList())).thenReturn(List.of(CompletableFuture.completedFuture(null)));
        return channel;
    }

    @Test
    void theDryRunCountsWhatASweepWouldDeleteAndDeletesNothing() {
        List<Message> history = List.of(message(5, OLD, true, 0, "bot post"), message(4, OLD, false, 0, "person"), message(3, OLD, true, 1, "bot file"));
        GuildMessageChannel channel = channelWith(history);
        AutoPurgeService service = new AutoPurgeService(mock(AutoPurgeRepository.class));

        AutoPurgeService.Scan scan = service.scan(channel, rule(Authors.ONLY_BOTS, false, null, true), NOW, false);

        assertEquals(2, scan.matched());
        assertEquals(3, scan.scanned());
        assertFalse(scan.mayHaveMore());
        verify(channel, never()).purgeMessages(anyList());
    }

    @Test
    void aRealSweepDeletesOnlyTheMessagesTheRuleMatches() {
        Message bot = message(5, OLD, true, 0, "bot post");
        Message person = message(4, OLD, false, 0, "person");
        GuildMessageChannel channel = channelWith(List.of(bot, person));
        AutoPurgeService service = new AutoPurgeService(mock(AutoPurgeRepository.class));

        assertEquals(1, service.clean(channel, rule(Authors.ONLY_BOTS, false, null, true), NOW));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Message>> deleted = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(channel).purgeMessages(deleted.capture());
        assertEquals(List.of(bot), List.copyOf(deleted.getValue()));
    }

    @Test
    void anUnfilteredSweepStillDeletesEveryOldMessage() {
        Message bot = message(5, OLD, true, 0, "a");
        Message person = message(4, OLD, false, 0, "b");
        GuildMessageChannel channel = channelWith(List.of(bot, person));
        assertEquals(2, new AutoPurgeService(mock(AutoPurgeRepository.class)).clean(channel, new AutoPurgeRule(1, 2, 24 * 60, true), NOW));
    }

    @Test
    void aWebhookMessageCountsAsABot() {
        Message hook = message(5, OLD, false, 0, "webhook");
        when(hook.isWebhookMessage()).thenReturn(true);
        GuildMessageChannel channel = channelWith(List.of(hook));
        AutoPurgeService service = new AutoPurgeService(mock(AutoPurgeRepository.class));
        assertEquals(1, service.scan(channel, rule(Authors.ONLY_BOTS, false, null, true), NOW, false).matched());
        assertEquals(0, service.scan(channel, rule(Authors.ONLY_HUMANS, false, null, true), NOW, false).matched());
    }
}
