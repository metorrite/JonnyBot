package com.younglings.bot.internal;

import com.younglings.bot.embedder.EmbedderRepository;
import com.younglings.bot.embedder.EmbedderRepository.Stored;
import com.younglings.bot.embedder.EmbedderService;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.welcome.WelcomeConfig.MessageType;
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

/** The Message embedder's routes: drafts, posting, editing in place, cloning and deleting, and the guards around them. */
class EmbedderAdminApiTest {
    private static final long GUILD = 1L;
    private static final String SIMPLE = "{\"messageType\":\"MESSAGE\",\"content\":\"Hello\"}";
    private static final String CONTAINER = "{\"messageType\":\"CONTAINER\",\"title\":\"Hi\",\"description\":\"There\"}";

    private EmbedderRepository repository;
    private EmbedderService service;
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
        api = new EmbedderAdminApi(repository, service);
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
        verify(service, never()).delete(any(), any(), anyLong(), anyLong());
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
    void postingADraftRecordsTheDiscordMessage() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, null, null), stored(5L, SIMPLE, 20L, 30L));
        when(service.post(eq(guild), eq(actor), any(), eq(20L))).thenReturn(30L);

        DataObject posted = call("POST", () -> "{\"channelId\":\"20\"}", "embedder", "messages", "5", "post");

        verify(repository).markPosted(GUILD, 5L, 20L, 30L);
        assertEquals("POSTED", posted.getString("status"));
    }

    @Test
    void anEmptyMessageIsNotPosted() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, "{\"messageType\":\"MESSAGE\",\"content\":\"\"}", null, null));

        assertThrows(ApiError.class, () -> call("POST", () -> "{\"channelId\":\"20\"}", "embedder", "messages", "5", "post"));
        verify(service, never()).post(any(), any(), any(), anyLong());
    }

    @Test
    void anAlreadyPostedMessageIsNotPostedAgain() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, 20L, 30L));

        assertThrows(ApiError.class, () -> call("POST", () -> "{\"channelId\":\"21\"}", "embedder", "messages", "5", "post"));
        verify(service, never()).post(any(), any(), any(), anyLong());
    }

    @Test
    void savingWithApplyEditsTheLiveMessageInPlace() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, 20L, 30L));

        call("PUT", () -> "{\"name\":\"Rules\",\"apply\":true,\"message\":{\"messageType\":\"MESSAGE\",\"content\":\"Changed\"}}", "embedder", "messages", "5");

        verify(service).edit(eq(guild), eq(actor), any(), any(), eq(20L), eq(30L));
        verify(repository).saveBody(eq(GUILD), eq(5L), eq("Rules"), anyString());
    }

    @Test
    void savingADraftNeverTouchesDiscord() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, null, null));

        call("PUT", () -> "{\"name\":\"Rules\",\"apply\":true,\"message\":{\"messageType\":\"MESSAGE\",\"content\":\"Changed\"}}", "embedder", "messages", "5");

        verify(service, never()).edit(any(), any(), any(), any(), anyLong(), anyLong());
        verify(repository).saveBody(eq(GUILD), eq(5L), eq("Rules"), anyString());
    }

    @Test
    void aRefusedLiveEditKeepsTheStoredCopyUnchanged() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, CONTAINER, 20L, 30L));
        org.mockito.Mockito.doThrow(new IllegalStateException("kind")).when(service).edit(any(), any(), any(), any(), anyLong(), anyLong());

        assertThrows(ApiError.class, () -> call("PUT", () -> "{\"name\":\"Rules\",\"apply\":true,\"message\":{\"messageType\":\"MESSAGE\",\"content\":\"x\"}}", "embedder", "messages", "5"));
        verify(repository, never()).saveBody(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void cloningPostsToExactlyOneChannelAndKeepsTheOriginal() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, 20L, 30L));
        when(repository.get(GUILD, 6L)).thenReturn(stored(6L, SIMPLE, 21L, 31L));
        when(service.post(eq(guild), eq(actor), any(), eq(21L))).thenReturn(31L);
        when(repository.create(eq(GUILD), eq("Rules (copy)"), anyString(), eq(21L), eq(31L), eq(9L))).thenReturn(6L);

        DataObject copy = call("POST", () -> "{\"channelId\":\"21\"}", "embedder", "messages", "5", "clone");

        assertEquals("6", copy.getString("id"));
        verify(service).post(eq(guild), eq(actor), any(), eq(21L));
        verify(repository, never()).delete(anyLong(), anyLong());
    }

    @Test
    void cloningIsRefusedAtTheStorageLimit() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, 20L, 30L));
        when(repository.count(GUILD)).thenReturn(EmbedderService.MAX_MESSAGES_PER_GUILD);

        assertThrows(ApiError.class, () -> call("POST", () -> "{\"channelId\":\"21\"}", "embedder", "messages", "5", "clone"));
        verify(service, never()).post(any(), any(), any(), anyLong());
    }

    @Test
    void deletingAPostedMessageRemovesItFromDiscordThenTheList() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, 20L, 30L));

        DataObject result = call("POST", () -> "{}", "embedder", "messages", "5", "delete");

        assertTrue(result.getBoolean("deleted"));
        verify(service).delete(guild, actor, 20L, 30L);
        verify(repository).delete(GUILD, 5L);
    }

    @Test
    void deletingADraftNeverTouchesDiscord() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, null, null));

        call("POST", () -> "{}", "embedder", "messages", "5", "delete");

        verify(service, never()).delete(any(), any(), anyLong(), anyLong());
        verify(repository).delete(GUILD, 5L);
    }

    @Test
    void aFailedDiscordDeleteKeepsTheEntry() {
        when(repository.get(GUILD, 5L)).thenReturn(stored(5L, SIMPLE, 20L, 30L));
        org.mockito.Mockito.doThrow(new IllegalStateException("nope")).when(service).delete(any(), any(), anyLong(), anyLong());

        assertThrows(ApiError.class, () -> call("POST", () -> "{}", "embedder", "messages", "5", "delete"));
        verify(repository, never()).delete(anyLong(), anyLong());
    }

    @Test
    void aContainerCannotBecomeAnotherKindOnceItIsPosted() {
        assertTrue(EmbedderService.changesKind(MessageType.CONTAINER, MessageType.EMBED));
        assertTrue(EmbedderService.changesKind(MessageType.MESSAGE, MessageType.CONTAINER));
        assertFalse(EmbedderService.changesKind(MessageType.MESSAGE, MessageType.EMBED_TEXT));
        assertFalse(EmbedderService.changesKind(MessageType.CONTAINER, MessageType.CONTAINER));
    }
}
