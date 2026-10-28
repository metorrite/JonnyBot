package com.runeclans.bot.feed;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedServiceTest {
    private final FeedRepository repository = mock(FeedRepository.class);
    private final FeedService service = new FeedService(repository, mock(FeedFetcher.class));
    private final FeedSubscription sub = new FeedSubscription(7, 1, FeedKind.REDDIT, "runescape", "", 2, "{title}", true);

    private static FeedItem item(String id) {
        return new FeedItem(id, "Title " + id, "https://x/" + id, "a");
    }

    @Test
    void firstReadOnlyRecordsAndPostsNothing() {
        when(repository.isPrimed(7)).thenReturn(false);
        assertEquals(List.of(), service.fresh(sub, List.of(item("3"), item("2"), item("1"))));
        verify(repository).markSeen(7, List.of("3", "2", "1"));
    }

    @Test
    void laterReadsPostOnlyUnseenOldestFirst() {
        when(repository.isPrimed(7)).thenReturn(true);
        when(repository.seen(7)).thenReturn(Set.of("1"));
        assertEquals(List.of("Title 2", "Title 3"), service.fresh(sub, List.of(item("3"), item("2"), item("1"))));
        ArgumentCaptor<List<String>> recorded = ArgumentCaptor.forClass(List.class);
        verify(repository).markSeen(anyLong(), recorded.capture());
        assertEquals(Set.of("3", "2"), new HashSet<>(recorded.getValue()));
    }

    @Test
    void nothingNewDoesNotTouchTheDatabase() {
        when(repository.isPrimed(7)).thenReturn(true);
        when(repository.seen(7)).thenReturn(Set.of("1", "2"));
        assertEquals(List.of(), service.fresh(sub, List.of(item("2"), item("1"))));
        verify(repository, never()).markSeen(anyLong(), anyList());
    }

    @Test
    void aBurstIsCappedToTheNewestFew() {
        when(repository.isPrimed(7)).thenReturn(true);
        when(repository.seen(7)).thenReturn(Set.of());
        List<String> posted = service.fresh(sub, List.of(item("5"), item("4"), item("3"), item("2"), item("1")));
        assertEquals(List.of("Title 3", "Title 4", "Title 5"), posted);
    }
}
