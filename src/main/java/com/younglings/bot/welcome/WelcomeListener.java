package com.younglings.bot.welcome;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Welcomes each new member. Like every listener in this bot it has to be added by hand in {@code Bot.createJDA};
 * a {@code @BService} alone is never registered with JDA.
 */
@BService
public class WelcomeListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(WelcomeListener.class);

    private final WelcomeService service;

    public WelcomeListener(WelcomeService service) {
        this.service = service;
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        try {
            service.welcomeNewMember(event.getMember());
        } catch (Exception e) {
            log.error("Welcoming {} failed", event.getMember().getId(), e);
        }
    }
}
