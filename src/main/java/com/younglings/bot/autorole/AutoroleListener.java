package com.younglings.bot.autorole;

import com.younglings.bot.BotListener;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Hands out autoroles when someone joins. Registered with JDA by {@code Bot} because it is a {@link BotListener}. */
@BService
public class AutoroleListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(AutoroleListener.class);

    private final AutoroleService service;

    public AutoroleListener(AutoroleService service) {
        this.service = service;
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        service.onLeave(event.getGuild().getIdLong(), event.getUser().getIdLong());
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        try {
            service.onJoin(event.getMember());
        } catch (Exception e) {
            log.error("Autoroles for {} failed", event.getMember().getId(), e);
        }
    }
}
