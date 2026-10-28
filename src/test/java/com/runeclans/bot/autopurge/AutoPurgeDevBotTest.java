package com.runeclans.bot.autopurge;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.alerts.LiveSwitch;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A bot that is not live deletes no messages on its own (T-187): the auto-clean sweep is the live bot's job. */
class AutoPurgeDevBotTest {
    private final AutoPurgeRepository repository = mock(AutoPurgeRepository.class);
    private final AutoPurgeService service = new AutoPurgeService(repository);

    @AfterEach
    void reset() {
        AutoPostRouter.reset();
    }

    @Test
    void aBotThatIsNotLiveSkipsTheAutoCleanSweep() {
        LiveSwitch.set(false);
        service.sweep(mock(JDA.class));
        verify(repository, never()).all();
    }

    @Test
    void theLiveBotCleansAsBefore() {
        LiveSwitch.set(true);
        when(repository.all()).thenReturn(List.of());
        service.sweep(mock(JDA.class));
        verify(repository).all();
    }
}
