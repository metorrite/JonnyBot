package com.runeclans.bot.rolekeep;

import com.runeclans.bot.BotListener;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remembers sticky roles when someone leaves and gives kept roles back when they return. It listens to joins and leaves only, never to role changes,
 * so nothing it does can set off itself or the clan role sync. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class RoleKeepListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(RoleKeepListener.class);

    private final RoleKeepService service;

    public RoleKeepListener(RoleKeepService service) {
        this.service = service;
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        Member member = event.getMember();
        if (member == null) return; // not cached: nothing to remember
        try {
            service.onLeave(event.getGuild(), event.getUser().getIdLong(), member.getRoles());
        } catch (Exception e) {
            log.error("Remembering roles of {} failed", event.getUser().getId(), e);
        }
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        try {
            service.onJoin(event.getMember());
        } catch (Exception e) {
            log.error("Giving kept roles back to {} failed", event.getMember().getId(), e);
        }
    }
}
