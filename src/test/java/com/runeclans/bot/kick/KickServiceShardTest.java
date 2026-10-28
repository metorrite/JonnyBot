package com.runeclans.bot.kick;

import com.runeclans.bot.discord.ShardFixtures;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** T-700: a go-live alert for a server this process can't see is not claimed (and so not lost); the next round tries again. */
class KickServiceShardTest {
    private static final long GUILD = Long.parseLong("1".repeat(18));

    private final KickRepository repository = mock(KickRepository.class);
    private final KickService service = new KickService(repository, mock(KickClient.class));

    private static KickRepository.Streamer streamer(String posted) {
        return new KickRepository.Streamer(1, GUILD, "someone", "Someone", 2, "", null, true, true, posted, posted == null ? null : 3L, posted == null ? null : 2L, 99);
    }

    private static KickClient.Stream live() {
        return new KickClient.Stream("s2", "someone", "Someone", "Title", "", 5, "");
    }

    @Test
    void anAlertOnAnotherShardIsNotClaimed() {
        service.handle(ShardFixtures.notOwned(GUILD), streamer(null), live());
        verify(repository, never()).claim(anyLong(), any(), any());
    }

    @Test
    void anAlertWhileTheShardIsConnectingIsNotClaimed() {
        service.handle(ShardFixtures.owned(GUILD, JDA.Status.CONNECTING_TO_WEBSOCKET, null), streamer(null), live());
        verify(repository, never()).claim(anyLong(), any(), any());
    }

    @Test
    void anAlertForAServerTheBotLeftIsClaimedAndDroppedAsBefore() {
        service.handle(ShardFixtures.owned(GUILD, JDA.Status.CONNECTED, null), streamer(null), live());
        verify(repository).claim(1, "s2", null);
    }

    @Test
    void anEndedStreamOnAnotherShardKeepsItsPostedMessage() {
        service.handle(ShardFixtures.notOwned(GUILD), streamer("s1"), null);
        verify(repository, never()).clearPosted(anyLong());
    }
}
