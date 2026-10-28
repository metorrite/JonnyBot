package com.runeclans.bot.suggestion;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuggestionServiceTest {
    private final SuggestionService service = new SuggestionService(Mockito.mock(SuggestionRepository.class));

    @Test
    void theFirstSuggestionIsNeverMadeToWait() {
        assertEquals(0, service.claimCooldown(1L, 1_000_000L));
    }

    @Test
    void aSecondOneRightAfterIsToldHowLongToWait() {
        service.claimCooldown(1L, 1_000_000L);

        assertEquals(30, service.claimCooldown(1L, 1_000_000L));
        assertEquals(10, service.claimCooldown(1L, 1_020_000L));
    }

    @Test
    void afterTheCooldownTheyMaySuggestAgain() {
        service.claimCooldown(1L, 1_000_000L);

        assertEquals(0, service.claimCooldown(1L, 1_030_000L));
    }

    @Test
    void peopleDoNotWaitForEachOther() {
        service.claimCooldown(1L, 1_000_000L);

        assertEquals(0, service.claimCooldown(2L, 1_000_000L));
    }

    @Test
    void decidedSuggestionsCloseVotingAndPendingOnesAreOpen() {
        Suggestion pending = suggestion(Suggestion.Status.PENDING);

        assertTrue(pending.open());
        for (var status : new Suggestion.Status[]{Suggestion.Status.APPROVED, Suggestion.Status.DENIED, Suggestion.Status.IMPLEMENTED}) {
            assertEquals(false, suggestion(status).open());
        }
    }

    @Test
    void theMessageShowsTheResponseAndWhoDecided() {
        var s = new Suggestion(3, 1, 2, "More cats", 4L, 5L, Suggestion.Status.APPROVED, "Great idea", 9L, Instant.EPOCH);
        var embed = SuggestionMessage.embed(s);

        assertEquals("Suggestion #3", embed.getTitle());
        assertTrue(embed.getFields().stream().anyMatch(f -> "Response".equals(f.getName()) && "Great idea".equals(f.getValue())));
        assertTrue(embed.getFields().stream().anyMatch(f -> "Decided by".equals(f.getName()) && "<@9>".equals(f.getValue())));
    }

    @Test
    void voteCountsOnlyShowOnceSomeoneHasVoted() {
        assertEquals("", SuggestionMessage.voteLabel(0));
        assertEquals("7", SuggestionMessage.voteLabel(7));
    }

    private static Suggestion suggestion(Suggestion.Status status) {
        return new Suggestion(1, 1, 2, "x", 3L, 4L, status, null, null, Instant.EPOCH);
    }
}
