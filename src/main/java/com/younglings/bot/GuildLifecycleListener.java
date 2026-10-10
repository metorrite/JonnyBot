package com.younglings.bot;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Notes when the bot is added to or removed from a server, so an operator can see it happen. A newly added server
 * starts with nothing configured (see {@code GuildSettingsService}), so nothing needs setting up here, and the
 * background jobs find the server on their own within minutes. A server's saved data is kept when the bot leaves, so
 * adding it back picks up where it left off; deleting it for good is a separate decision.
 * <p>
 * Like every listener in this bot it has to be added by hand in {@code Bot.createJDA}.
 */
@BService
public class GuildLifecycleListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(GuildLifecycleListener.class);

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        log.info("Added to server '{}' ({}), {} members. It starts with nothing configured.",
                event.getGuild().getName(), event.getGuild().getId(), event.getGuild().getMemberCount());
    }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        log.info("Removed from server '{}' ({}). Its saved data is kept.", event.getGuild().getName(), event.getGuild().getId());
    }
}
