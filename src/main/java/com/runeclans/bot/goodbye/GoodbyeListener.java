package com.runeclans.bot.goodbye;

import com.runeclans.bot.BotListener;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Announces departures. Registered with JDA by {@code Bot} because it is a {@link BotListener}. */
@BService
public class GoodbyeListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(GoodbyeListener.class);

    private final GoodbyeService service;

    public GoodbyeListener(GoodbyeService service) {
        this.service = service;
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        try {
            service.onLeave(event.getGuild(), event.getUser());
        } catch (Exception e) {
            log.error("The goodbye for {} failed", event.getUser().getId(), e);
        }
    }
}
