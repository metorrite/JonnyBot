package com.younglings.bot.autorole;

import com.younglings.bot.alerts.AutoPostRouter;
import com.younglings.bot.alerts.LiveSwitch;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A bot that is not live gives no delayed autoroles on its own (T-187): the live bot gives them. */
class AutoroleDevBotTest {
    private final AutoroleRepository repository = mock(AutoroleRepository.class);
    private final AutoroleService service = new AutoroleService(repository);

    @AfterEach
    void reset() {
        AutoPostRouter.reset();
    }

    @Test
    void aBotThatIsNotLiveSkipsTheDelayedAutoroleSweep() {
        LiveSwitch.set(false);
        service.sweep(mock(JDA.class));
        verify(repository, never()).duePending(any(Instant.class), anyInt());
    }

    @Test
    void theLiveBotGivesDueRolesAsBefore() {
        LiveSwitch.set(true);
        when(repository.duePending(any(Instant.class), anyInt())).thenReturn(List.of());
        service.sweep(mock(JDA.class));
        verify(repository).duePending(any(Instant.class), anyInt());
    }
}
