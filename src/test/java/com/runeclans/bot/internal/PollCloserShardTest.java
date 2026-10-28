package com.runeclans.bot.internal;

import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.commands.poll.PollSession;
import com.runeclans.bot.discord.ShardFixtures;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T-700: a due poll on another process's shard keeps its end time; a poll in a server the bot left loses it as before. */
class PollCloserShardTest {
    private static final long GUILD = Long.parseLong("1".repeat(18));
    private static final long POLL = 7;

    private final CommunitySettings settings = mock(CommunitySettings.class);
    private final PollService polls = mock(PollService.class);
    private final PollCloser closer = new PollCloser(settings, polls, mock(SiteCache.class));

    private void duePoll() {
        PollSession poll = mock(PollSession.class);
        when(poll.guildId()).thenReturn(GUILD);
        when(settings.due(any(OffsetDateTime.class))).thenReturn(List.of(POLL));
        when(polls.getSessionById(POLL)).thenReturn(poll);
    }

    @Test
    void aPollOnAnotherShardKeepsItsEndTime() {
        duePoll();
        closer.closeDue(ShardFixtures.notOwned(GUILD));
        verify(settings, never()).clearSchedule(anyLong());
        verify(polls, never()).closePoll(any(), anyLong());
    }

    @Test
    void aPollWhileItsShardIsConnectingKeepsItsEndTime() {
        duePoll();
        closer.closeDue(ShardFixtures.owned(GUILD, JDA.Status.CONNECTING_TO_WEBSOCKET, null));
        verify(settings, never()).clearSchedule(anyLong());
    }

    @Test
    void aPollInAServerTheBotLeftIsClearedAsBefore() {
        duePoll();
        closer.closeDue(ShardFixtures.owned(GUILD, JDA.Status.CONNECTED, null));
        verify(settings).clearSchedule(POLL);
    }

    @Test
    void aPollThatNoLongerExistsIsCleared() {
        when(settings.due(any(OffsetDateTime.class))).thenReturn(List.of(POLL));
        closer.closeDue(ShardFixtures.notOwned(GUILD));
        verify(settings).clearSchedule(POLL);
    }
}
