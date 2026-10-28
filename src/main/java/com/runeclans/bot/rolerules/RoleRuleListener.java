package com.runeclans.bot.rolerules;

import com.runeclans.bot.BotListener;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Hands role changes to {@link RoleRuleService}. Registered with JDA by {@code Bot} because it is a {@link BotListener}. */
@BService
public class RoleRuleListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(RoleRuleListener.class);

    private final RoleRuleService service;

    public RoleRuleListener(RoleRuleService service) {
        this.service = service;
    }

    @Override
    public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event) {
        handle(event.getGuild(), event.getMember(), event.getRoles(), true);
    }

    @Override
    public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event) {
        handle(event.getGuild(), event.getMember(), event.getRoles(), false);
    }

    private void handle(net.dv8tion.jda.api.entities.Guild guild, net.dv8tion.jda.api.entities.Member member, List<Role> roles, boolean added) {
        try {
            Set<Long> changed = new HashSet<>();
            for (Role role : roles) changed.add(role.getIdLong());
            service.onRoleChange(guild, member, changed, added);
        } catch (Exception e) {
            log.error("Role rules for {} failed", member.getId(), e);
        }
    }
}
