package com.runeclans.bot.rolekeep;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.alerts.LiveSwitch;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.permission.PermissionGroupService;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A bot that is not live takes no timed roles away on its own (T-187): the live bot ends them, and may have extended them since the copy was made. */
class RoleKeepDevBotTest {
    private final RoleKeepRepository repository = mock(RoleKeepRepository.class);
    private final RoleKeepService service = new RoleKeepService(repository, mock(GuildSettingsService.class), mock(PermissionGroupService.class));

    @AfterEach
    void reset() {
        AutoPostRouter.reset();
    }

    @Test
    void aBotThatIsNotLiveSkipsTheTimedRoleSweep() {
        LiveSwitch.set(false);
        service.expire(mock(JDA.class));
        verify(repository, never()).expired(anyInt());
    }

    @Test
    void theLiveBotEndsTimedRolesAsBefore() {
        LiveSwitch.set(true);
        when(repository.expired(anyInt())).thenReturn(List.of());
        service.expire(mock(JDA.class));
        verify(repository).expired(anyInt());
    }
}
