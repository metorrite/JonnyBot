package com.younglings.bot;

import com.younglings.bot.retention.GuildRetentionService;
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
 * adding it back picks up where it left off, and {@link GuildRetentionService} deletes it for good if the bot stays away.
 * <p>
 * Like every listener in this bot it has to be added by hand in {@code Bot.createJDA}.
 */
@BService
public class GuildLifecycleListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(GuildLifecycleListener.class);

    private final GuildRetentionService retention;

    public GuildLifecycleListener(GuildRetentionService retention) {
        this.retention = retention;
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        log.info("Added to server '{}' ({}), {} members. It starts with nothing configured.",
                event.getGuild().getName(), event.getGuild().getId(), event.getGuild().getMemberCount());
        retention.onJoined(event.getGuild().getIdLong()); // back before its data was deleted: keep it
    }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        log.info("Removed from server '{}' ({}). Its saved data is kept for now, then deleted if the bot isn't added back (see GUILD_DATA_RETENTION_DAYS).",
                event.getGuild().getName(), event.getGuild().getId());
        retention.onLeft(event.getGuild().getIdLong());
    }
}
