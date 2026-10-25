package com.younglings.bot.discord.jobs;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** A handler may do several kinds of job, each with its own answer to "safe to run twice?" (T-456 phase 2). */
class DiscordOutboxKindsTest {
    /** Posts (not safe twice) and deletes (safe twice). */
    private static final class PostAndDelete implements DiscordJobHandler {
        @Override
        public String kind() {
            return "thing.post";
        }

        @Override
        public List<String> kinds() {
            return List.of("thing.post", "thing.delete");
        }

        @Override
        public boolean idempotent() {
            return false;
        }

        @Override
        public boolean idempotent(String kind) {
            return kind.equals("thing.delete");
        }

        @Override
        public DataObject run(JDA jda, DiscordJob job) {
            return null;
        }
    }

    @Test
    void everyKindOfAHandlerCanBeQueuedAndOnlyTheSafeOnesAreRecoveredAfterARestart() {
        DiscordJobRepository repository = mock(DiscordJobRepository.class);
        DiscordOutbox outbox = new DiscordOutbox(repository, List.of(new PostAndDelete()));

        assertEquals(List.of("thing.delete"), outbox.idempotentKinds());
        assertDoesNotThrow(() -> outbox.enqueue("thing.post", 1L, 2L, null, DataObject.empty()));
        assertDoesNotThrow(() -> outbox.enqueue("thing.delete", 1L, 2L, null, DataObject.empty()));
        assertThrows(IllegalArgumentException.class, () -> outbox.enqueue("thing.edit", 1L, 2L, null, DataObject.empty()));
    }
}
