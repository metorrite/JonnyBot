package com.runeclans.bot.kick;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.tracking.TrackingPostingToggle;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A bot that is not live (T-126) makes no Kick go-live posts: they are tracked and edited later, like Twitch's. */
class KickServiceDivertTest {
    private final KickRepository repository = mock(KickRepository.class);
    private final KickService service = new KickService(repository, mock(KickClient.class));

    @AfterEach
    void reset() {
        AutoPostRouter.reset();
    }

    /** Builds the router the way the bot does, with posting switched on (live) or off (not live). */
    private static void router(boolean live) {
        TrackingPostingToggle posting = mock(TrackingPostingToggle.class);
        when(posting.isEnabled()).thenReturn(live);
        new AutoPostRouter(mock(BotConfig.class), posting);
    }

    @Test
    void aBotThatIsNotLiveDoesNotPollKick() {
        router(false);
        service.poll(mock(JDA.class));
        verify(repository, never()).active();
    }

    @Test
    void theLiveBotPollsAsBefore() {
        router(true);
        when(repository.active()).thenReturn(List.of());
        service.poll(mock(JDA.class));
        verify(repository).active();
    }
}
