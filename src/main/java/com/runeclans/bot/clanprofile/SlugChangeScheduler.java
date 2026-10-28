package com.runeclans.bot.clanprofile;

import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Applies the address changes whose hour is over, once a minute, so a site moves even when nobody opens its admin. */
@BService
public class SlugChangeScheduler {
    private final SlugChangeService service;
    private final ScheduledExecutorService executor = BotScheduler.lane("address-change-scheduler");

    public SlugChangeScheduler(SlugChangeService service) {
        this.service = service;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        executor.scheduleWithFixedDelay(service::applyAllDue, 1, 1, TimeUnit.MINUTES);
    }
}
