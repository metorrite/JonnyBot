package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.embedder.EmbedderRepository;
import com.runeclans.bot.embedder.EmbedderRepository.Stored;
import com.runeclans.bot.embedder.EmbedderService;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.welcome.WelcomeConfig.MessageType;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Message embedder's routes: drafts, posting, editing in place, cloning and deleting, and the guards around them. Discord is only reached through queued jobs. */
class EmbedderAdminApiTest {
    private static final long GUILD = 1L;
    private static final String SIMPLE = "{\"messageType\":\"MESSAGE\",\"content\":\"Hello\"}";
    private static final String CONTAINER = "{\"messageType\":\"CONTAINER\",\"title\":\"Hi\",\"description\":\"There\"}";

    private EmbedderRepository repository;
    private EmbedderService service;
    private DiscordOutbox outbox;
    private EmbedderAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        repository = mock(EmbedderRepository.class);
        service = mock(EmbedderService.class);
        guild = mock(Guild.class);
        actor = mock(Member.class);
        User user = mock(User.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getId()).thenReturn(Long.toString(GUILD));
        when(actor.getUser()).thenReturn(user);
        when(user.getIdLong()).thenReturn(9L);
        outbox = com.runeclans.bot.testsupport.QueuedJobs.outbox();
        api = new EmbedderAdminApi(repository, service, outbox);
    }

    private Stored stored(long id, String bodyJson, Long channelId, Long messageId) {
        return new Stored(id, GUILD, "Rules", bodyJson, channelId, messageId, 9L, OffsetDateTime.now(), messageId == null ? null : OffsetDateTime.now());
    }

    private DataObject call(String method, java.util.function.Supplier<String> body, String... parts) {
        return api.route(guild, actor, method, parts, () -> DataObject.fromJson(body.get()));
    }

    @Test
    void otherPathsAreNotThisFeatures() {
        assertNull(api.route(guild, actor, "GET", new String[]{"welcome"}, () -> null));
    }

    @Test
    void aMessageFromAnotherServerIsNotFound() {
        when(repository.get(GUILD, 5L)).thenReturn(null);

        ApiError e = assertThrows(ApiError.class, () -> call("GET", () -> "{}", "embedder", "messages", "5"));
        assertEquals(404, e.status);
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void aDraftCanBeSavedUnfinishedButNeedsAName() {
        when(repository.create(eq(GUILD), eq("Rules"), anyString(), any(), any(), eq(9L))).thenReturn(5L);
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, "{\"messageType\":\"MESSAGE\",\"content\":\"\"}", null, null));

        DataObject saved = call("POST", () -> "{\"name\":\"Rules\",\"message\":{\"messageType\":\"MESSAGE\",\"content\":\"\"}}", "embedder", "messages");
        assertEquals("DRAFT", saved.getString("status"));

        assertThrows(ApiError.class, () -> call("POST", () -> "{\"name\":\" \",\"message\":" + SIMPLE + "}", "embedder", "messages"));
    }

    @Test
    void postingADraftQueuesItAndAnswersTheJob() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, null, null));

        DataObject answer = call("POST", () -> "{\"channelId\":\"20\"}", "embedder", "messages", "5", "post");

        verify(service).checkedChannel(guild, actor, 20L);
        verify(outbox).enqueue(eq(EmbedderJobs.POST), eq(GUILD), anyLong(), eq("embedder-post:1:5"), any());
        verify(repository, never()).markPosted(anyLong(), anyLong(), anyLong(), anyLong());
        assertEquals("DRAFT", answer.getString("status"), "it shows as posted once the job has posted it");
        assertEquals("77", answer.getObject("job").getString("id"));
    }

    @Test
    void aChannelTheAdminCantPostInIsRefusedBeforeAnythingIsQueued() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, null, null));
        when(service.checkedChannel(guild, actor, 20L)).thenThrow(new IllegalStateException("You can't post in #x yourself"));

        ApiError e = assertThrows(ApiError.class, () -> call("POST", () -> "{\"channelId\":\"20\"}", "embedder", "messages", "5", "post"));
        assertEquals(400, e.status);
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void anEmptyMessageIsNotPosted() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, "{\"messageType\":\"MESSAGE\",\"content\":\"\"}", null, null));

        assertThrows(ApiError.class, () -> call("POST", () -> "{\"channelId\":\"20\"}", "embedder", "messages", "5", "post"));
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void anAlreadyPostedMessageIsNotPostedAgain() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, 20L, 30L));

        assertThrows(ApiError.class, () -> call("POST", () -> "{\"channelId\":\"21\"}", "embedder", "messages", "5", "post"));
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void savingWithApplySavesThenQueuesTheLiveEdit() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, 20L, 30L));

        DataObject answer = call("PUT", () -> "{\"name\":\"Rules\",\"apply\":true,\"message\":{\"messageType\":\"MESSAGE\",\"content\":\"Changed\"}}", "embedder", "messages", "5");

        verify(repository).saveBody(eq(GUILD), eq(5L), eq("Rules"), anyString());
        verify(outbox).enqueue(eq(EmbedderJobs.EDIT), eq(GUILD), anyLong(), eq("embedder-edit:1:5"), any());
        assertEquals("77", answer.getObject("job").getString("id"));
    }

    @Test
    void savingADraftNeverTouchesDiscord() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, null, null));

        DataObject answer = call("PUT", () -> "{\"name\":\"Rules\",\"apply\":true,\"message\":{\"messageType\":\"MESSAGE\",\"content\":\"Changed\"}}", "embedder", "messages", "5");

        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), any(), any());
        verify(repository).saveBody(eq(GUILD), eq(5L), eq("Rules"), anyString());
        assertFalse(answer.hasKey("job"));
    }

    @Test
    void aPostedContainerCantBecomeAnotherKindAndTheStoredCopyStays() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, CONTAINER, 20L, 30L));

        assertThrows(ApiError.class, () -> call("PUT", () -> "{\"name\":\"Rules\",\"apply\":true,\"message\":{\"messageType\":\"MESSAGE\",\"content\":\"x\"}}", "embedder", "messages", "5"));
        verify(repository, never()).saveBody(anyLong(), anyLong(), anyString(), anyString());
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void cloningKeepsACopyAndQueuesItForExactlyOneChannel() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, 20L, 30L));
        when(repository.get(GUILD, 6L)).thenReturn(stored(6L, SIMPLE, null, null));
        when(repository.create(eq(GUILD), eq("Rules (copy)"), anyString(), eq(null), eq(null), eq(9L))).thenReturn(6L);

        DataObject copy = call("POST", () -> "{\"channelId\":\"21\"}", "embedder", "messages", "5", "clone");

        assertEquals("6", copy.getString("id"));
        verify(outbox).enqueue(eq(EmbedderJobs.POST), eq(GUILD), anyLong(), eq("embedder-post:1:6"), any());
        verify(repository, never()).delete(anyLong(), anyLong());
    }

    @Test
    void cloningIsRefusedAtTheStorageLimit() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, 20L, 30L));
        when(repository.count(GUILD)).thenReturn(EmbedderService.MAX_MESSAGES_PER_GUILD);

        assertThrows(ApiError.class, () -> call("POST", () -> "{\"channelId\":\"21\"}", "embedder", "messages", "5", "clone"));
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void deletingAPostedMessageQueuesItsRemovalAndDropsTheEntry() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, 20L, 30L));
        when(service.mayDeleteIn(guild, actor, 20L)).thenReturn(true);

        DataObject result = call("POST", () -> "{}", "embedder", "messages", "5", "delete");

        assertTrue(result.getBoolean("deleted"));
        verify(outbox).enqueue(eq(EmbedderJobs.DELETE), eq(GUILD), anyLong(), eq(null), any());
        verify(repository).delete(GUILD, 5L);
    }

    @Test
    void deletingADraftNeverTouchesDiscord() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, null, null));

        call("POST", () -> "{}", "embedder", "messages", "5", "delete");

        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), any(), any());
        verify(repository).delete(GUILD, 5L);
    }

    @Test
    void aDeleteTheAdminMayNotMakeKeepsTheEntry() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, 20L, 30L));
        when(service.mayDeleteIn(any(), any(), anyLong())).thenThrow(new IllegalStateException("nope"));

        assertThrows(ApiError.class, () -> call("POST", () -> "{}", "embedder", "messages", "5", "delete"));
        verify(repository, never()).delete(anyLong(), anyLong());
        verify(outbox, never()).enqueue(anyString(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void aContainerCannotBecomeAnotherKindOnceItIsPosted() {
        assertTrue(EmbedderService.changesKind(MessageType.CONTAINER, MessageType.EMBED));
        assertTrue(EmbedderService.changesKind(MessageType.MESSAGE, MessageType.CONTAINER));
        assertFalse(EmbedderService.changesKind(MessageType.MESSAGE, MessageType.EMBED_TEXT));
        assertFalse(EmbedderService.changesKind(MessageType.CONTAINER, MessageType.CONTAINER));
    }
}
