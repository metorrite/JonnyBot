package com.younglings.bot.commands.poll;

import com.younglings.bot.poll.PollRepository;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PollService keeps its boot-loaded polls only as a cache: a poll another process made (the website) is read from the database on a miss,
 * so its buttons and votes work without a restart. A miss that finds nothing is remembered briefly; a failed read is not remembered.
 */
class PollLoadThroughTest {
    private static final long GUILD = 1L, CHANNEL = 2L, OWNER = 10L, VOTER = 11L;

    private static PollSession poll(long id, String status, Long messageId) {
        return new PollSession(id, GUILD, CHANNEL, messageId, "Poll " + id, false, false, status, OWNER);
    }

    private static PollRepository emptyRepository() {
        PollRepository repository = mock(PollRepository.class);
        when(repository.getAllActivePolls()).thenReturn(List.of());
        return repository;
    }

    @Test
    void aPollMadeElsewhereIsReadOnceAndThenServedFromTheCache() {
        PollRepository repository = emptyRepository();
        PollService service = new PollService(repository);
        when(repository.getPollById(7)).thenReturn(poll(7, "ACTIVE", 70L));

        assertEquals("Poll 7", service.getSessionById(7).title());
        assertEquals("Poll 7", service.getSessionById(7).title());
        verify(repository, times(1)).getPollById(7);
    }

    @Test
    void anEndedOrUnknownPollIsNotCachedAsActiveAndTheMissIsRememberedBriefly() {
        PollRepository repository = emptyRepository();
        PollService service = new PollService(repository);
        when(repository.getPollById(8)).thenReturn(poll(8, "CLOSED", 80L));

        assertNull(service.getSessionById(8));
        assertNull(service.getSessionById(8));
        assertNull(service.getSessionById(9));
        verify(repository, times(1)).getPollById(8);
        verify(repository, times(1)).getPollById(9);
    }

    @Test
    void aFailedReadReturnsNothingAndIsTriedAgainNextTime() {
        PollRepository repository = emptyRepository();
        PollService service = new PollService(repository);
        when(repository.getPollById(anyLong())).thenThrow(new RuntimeException("database down"));

        assertNull(service.getSessionById(7));
        assertNull(service.getSessionById(7));
        verify(repository, times(2)).getPollById(7);
    }

    @Test
    void aVoteOnAPollMadeElsewhereCountsInsteadOfSayingThePollEnded() {
        PollRepository repository = emptyRepository();
        PollService service = new PollService(repository);
        when(repository.getPollById(7)).thenReturn(poll(7, "ACTIVE", 70L));

        assertEquals(PollService.VoteResult.ADDED, service.toggleVote(7, 701, VOTER));
        verify(repository).addVote(7, 701, VOTER);
    }

    @Test
    void optionsAreCachedOnlyOnceThePollIsPosted() {
        PollRepository repository = emptyRepository();
        PollService service = new PollService(repository);
        List<PollOption> options = List.of(new PollOption(701, 7, 1, "A"), new PollOption(702, 7, 2, "B"));
        when(repository.getOptions(7)).thenReturn(options);
        when(repository.getOptions(8)).thenReturn(options);
        when(repository.getPollById(7)).thenReturn(poll(7, "ACTIVE", 70L));
        when(repository.getPollById(8)).thenReturn(poll(8, "ACTIVE", null)); // still being created by another process

        assertNotNull(service.getSessionById(7));
        assertNotNull(service.getSessionById(8));
        service.getOptions(7);
        service.getOptions(7);
        service.getOptions(8);
        service.getOptions(8);
        verify(repository, times(1)).getOptions(7);
        verify(repository, times(2)).getOptions(8);
    }

    @Test
    void thePanelListsPollsFromTheDatabaseAndDropsOnesEndedElsewhere() {
        PollRepository repository = mock(PollRepository.class);
        when(repository.getAllActivePolls()).thenReturn(List.of(poll(3, "ACTIVE", 30L), poll(4, "ACTIVE", 40L)));
        PollService service = new PollService(repository);
        // poll 4 was ended on another process; poll 5 was started there
        when(repository.getActivePolls(GUILD)).thenReturn(List.of(poll(3, "ACTIVE", 30L), poll(5, "ACTIVE", 50L)));

        assertEquals(List.of(5L, 3L), service.activePolls(GUILD).stream().map(PollSession::pollId).toList());
        when(repository.getPollById(4)).thenReturn(poll(4, "CLOSED", 40L));
        assertNull(service.getSessionById(4), "the ended poll is no longer served from the cache");
        assertNotNull(service.getSessionById(5));
        verify(repository, times(0)).getPollById(5);
    }

    @Test
    void ifTheListCannotBeReadThePanelFallsBackToTheCache() {
        PollRepository repository = mock(PollRepository.class);
        when(repository.getAllActivePolls()).thenReturn(List.of(poll(3, "ACTIVE", 30L)));
        PollService service = new PollService(repository);
        when(repository.getActivePolls(GUILD)).thenThrow(new RuntimeException("database down"));

        assertEquals(Set.of(3L), Set.copyOf(service.activePolls(GUILD).stream().map(PollSession::pollId).toList()));
    }

    @Test
    void aPollCachedBeforeItsMessageWasPostedPicksUpTheMessageIdFromTheList() {
        PollRepository repository = emptyRepository();
        PollService service = new PollService(repository);
        // another process inserted the poll but has not saved its message id yet
        when(repository.getActivePolls(GUILD)).thenReturn(List.of(poll(7, "ACTIVE", null)));
        service.activePolls(GUILD);
        assertNull(service.getSessionById(7).messageId());

        when(repository.getActivePolls(GUILD)).thenReturn(List.of(poll(7, "ACTIVE", 70L)));
        assertEquals(70L, service.activePolls(GUILD).getFirst().messageId());
        assertEquals(70L, service.getSessionById(7).messageId());
    }

    @Test
    void updatingAMessageRereadsAPollCachedBeforeItsMessageWasPosted() {
        PollRepository repository = emptyRepository();
        PollService service = new PollService(repository);
        when(repository.getPollById(7)).thenReturn(poll(7, "ACTIVE", null));
        assertNull(service.getSessionById(7).messageId());

        when(repository.getPollById(7)).thenReturn(poll(7, "ACTIVE", 70L));
        service.updateMessage(mock(Guild.class), 7);
        assertEquals(70L, service.getSessionById(7).messageId());
    }
}
