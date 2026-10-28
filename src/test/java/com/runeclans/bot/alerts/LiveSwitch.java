package com.runeclans.bot.alerts;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.tracking.TrackingPostingToggle;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Builds the router the way the bot does, with posting switched on (the live bot) or off (a bot that is not live), for the sweep tests (T-187). */
public final class LiveSwitch {
    private LiveSwitch() {}

    public static void set(boolean live) {
        TrackingPostingToggle posting = mock(TrackingPostingToggle.class);
        when(posting.isEnabled()).thenReturn(live);
        new AutoPostRouter(mock(BotConfig.class), posting);
    }
}
