package com.younglings.bot.plugin;

import com.younglings.bot.plugin.PluginPayload.Event;
import com.younglings.bot.plugin.PluginPayload.Parsed;
import com.younglings.bot.plugin.PluginRepository.Token;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.PlayerLinkRepository.PlayerAccount;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

class PluginServiceTest {
    private static final long USER = 7L;
    private final PluginRepository repository = mock(PluginRepository.class);
    private final PlayerLinkRepository links = mock(PlayerLinkRepository.class);
    private final PluginEventSink sink = mock(PluginEventSink.class);
    private final PluginService service = new PluginService(repository, links, sink);

    private static Event drop(String key) {
        return new Event(key, PluginEventType.DROP, Instant.now(), "NPC", "Telos", null, "{\"itemName\":\"X\"}");
    }

    private static Event kill(String key) {
        return new Event(key, PluginEventType.KILL, Instant.now(), null, null, null, "{\"bossName\":\"Telos\"}");
    }

    private static Parsed batch(Event... events) {
        return new Parsed(null, "Jonny Young", "test 1", List.of(events), List.of());
    }

    private void ownedBy(long userId) {
        when(links.getAccountForRsn("Jonny Young")).thenReturn(new PlayerAccount(1, "Jonny Young", userId, "x", null, null));
    }

    @Test
    void aNameThePlayerHasNotLinkedIsRefusedAndNothingIsStored() {
        when(links.getAccountForRsn("Jonny Young")).thenReturn(null);

        var result = service.ingest(USER, batch(drop("drop-0000-1")));

        assertNotNull(result.forbidden());
        verify(repository, never()).insertEvent(anyLong(), anyString(), any(), anyString());
    }

    @Test
    void aNameLinkedToSomeoneElseIsRefused() {
        ownedBy(999L);

        assertNotNull(service.ingest(USER, batch(drop("drop-0000-1"))).forbidden());
    }

    @Test
    void onlyConsentedCategoriesAreStored() {
        ownedBy(USER);
        when(repository.consent(USER)).thenReturn(EnumSet.of(PluginCategory.DROPS));
        when(repository.insertEvent(anyLong(), anyString(), any(), anyString())).thenReturn(true);

        var result = service.ingest(USER, batch(drop("drop-0000-1"), kill("kill-0000-1")));

        assertEquals(1, result.accepted());
        assertEquals(0, result.duplicates());
        assertEquals(1, result.rejected().size());
        assertEquals("kill-0000-1", result.rejected().get(0).id());
        assertTrue(result.rejected().get(0).reason().startsWith("not_consented"));
        assertEquals(Set.of(PluginCategory.DROPS), result.consented());
    }

    @Test
    void withNoConsentNothingIsStoredAtAll() {
        ownedBy(USER);
        when(repository.consent(USER)).thenReturn(EnumSet.noneOf(PluginCategory.class));

        var result = service.ingest(USER, batch(drop("drop-0000-1"), kill("kill-0000-1")));

        assertEquals(0, result.accepted());
        assertEquals(2, result.rejected().size());
        verify(repository, never()).insertEvent(anyLong(), anyString(), any(), anyString());
    }

    @Test
    void aRetriedEventCountsAsADuplicateNotAnError() {
        ownedBy(USER);
        when(repository.consent(USER)).thenReturn(EnumSet.of(PluginCategory.DROPS));
        when(repository.insertEvent(anyLong(), anyString(), any(), anyString())).thenReturn(true, false);

        var result = service.ingest(USER, batch(drop("drop-0000-1"), drop("drop-0000-2")));

        assertEquals(1, result.accepted());
        assertEquals(1, result.duplicates());
        assertTrue(result.rejected().isEmpty());
    }

    @Test
    void onlyNewlyStoredEventsAreHandedToTheConsumersAndTheirFailureDoesNotFailTheUpload() {
        ownedBy(USER);
        when(repository.consent(USER)).thenReturn(EnumSet.of(PluginCategory.DROPS));
        when(repository.insertEvent(anyLong(), anyString(), any(), anyString())).thenReturn(true, false);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(sink).accepted(anyLong(), anyString(), any());

        var result = service.ingest(USER, batch(drop("drop-0000-1"), drop("drop-0000-2")));

        assertEquals(1, result.accepted());
        verify(sink).accepted(eq(USER), eq("Jonny Young"), org.mockito.ArgumentMatchers.argThat(list -> list.size() == 1 && list.get(0).key().equals("drop-0000-1")));
    }

    @Test
    void nothingIsHandedOnWhenNothingWasStored() {
        ownedBy(USER);
        when(repository.consent(USER)).thenReturn(EnumSet.of(PluginCategory.DROPS));
        when(repository.insertEvent(anyLong(), anyString(), any(), anyString())).thenReturn(false);

        service.ingest(USER, batch(drop("drop-0000-1")));

        verify(sink, never()).accepted(anyLong(), anyString(), any());
    }

    @Test
    void theEventIsStoredUnderTheLinkedNameAsItIsKept() {
        when(links.getAccountForRsn("Jonny Young")).thenReturn(new PlayerAccount(1, "Jonny YOUNG", USER, "x", null, null));
        when(repository.consent(USER)).thenReturn(EnumSet.of(PluginCategory.DROPS));
        when(repository.insertEvent(anyLong(), anyString(), any(), anyString())).thenReturn(true);

        service.ingest(USER, batch(drop("drop-0000-1")));

        verify(repository).insertEvent(eq(USER), eq("Jonny YOUNG"), any(Event.class), eq("test 1"));
    }

    @Test
    void aTokenIsShownOnceAndOnlyItsHashIsKept() {
        when(repository.activeTokenCount(USER)).thenReturn(0);
        when(repository.createToken(anyLong(), anyString(), anyString(), anyString())).thenReturn(42L);

        var created = service.createToken(USER, "  My   laptop ");

        assertEquals(42L, created.id());
        assertTrue(PluginTokens.looksLikeToken(created.token()));
        assertEquals("My laptop", created.label());
        verify(repository).createToken(USER, PluginTokens.hash(created.token()), created.preview(), "My laptop");
        assertNotEquals(created.token(), PluginTokens.hash(created.token()));
    }

    @Test
    void aPlayerCannotHoldMoreThanTheLimitOfActiveTokens() {
        when(repository.activeTokenCount(USER)).thenReturn(PluginTokens.MAX_ACTIVE_PER_USER);

        assertThrows(PluginService.PluginException.class, () -> service.createToken(USER, "x"));
        verify(repository, never()).createToken(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void junkAndRevokedTokensDoNotAuthenticate() {
        assertTrue(service.authenticate(null).isEmpty());
        assertTrue(service.authenticate("not-a-token").isEmpty());
        verify(repository, never()).activeByHash(anyString());

        String good = PluginTokens.generate(new SecureRandom());
        when(repository.activeByHash(PluginTokens.hash(good))).thenReturn(Optional.empty());
        assertTrue(service.authenticate(good).isEmpty(), "unknown or revoked");
    }

    @Test
    void aValidTokenAuthenticatesAndIsMarkedUsed() {
        String good = PluginTokens.generate(new SecureRandom());
        when(repository.activeByHash(PluginTokens.hash(good))).thenReturn(Optional.of(new Token(5, USER, "Plugin", "jbp_abcd…", Instant.now().minusSeconds(3600), null, null)));

        var token = service.authenticate(good);

        assertTrue(token.isPresent());
        assertEquals(USER, token.get().discordUserId());
        verify(repository).touch(5);
    }

    @Test
    void aRecentlyUsedTokenIsNotRewrittenOnEveryRequest() {
        String good = PluginTokens.generate(new SecureRandom());
        when(repository.activeByHash(PluginTokens.hash(good))).thenReturn(Optional.of(new Token(5, USER, "Plugin", "jbp_abcd…", Instant.now().minusSeconds(3600), Instant.now().minusSeconds(10), null)));

        assertFalse(service.authenticate(good).isEmpty());
        verify(repository, never()).touch(anyLong());
    }
}
