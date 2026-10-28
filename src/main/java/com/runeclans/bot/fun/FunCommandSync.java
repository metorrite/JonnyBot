package com.runeclans.bot.fun;

import io.github.freya022.botcommands.api.commands.application.ApplicationCommandsContext;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pushes a server's slash commands to Discord again, so a fun command appears in (or disappears from) that server's command list as
 * soon as it is switched on or off on the dashboard, instead of at the next restart. BotCommands compares with what Discord already
 * has and only sends a change, and {@link FunCommandFilter} decides which fun commands the server gets.
 */
@BService
public class FunCommandSync {
    private static final Logger log = LoggerFactory.getLogger(FunCommandSync.class);

    private final ApplicationCommandsContext commands;

    public FunCommandSync(ApplicationCommandsContext commands) {
        this.commands = commands;
    }

    /** Fire and forget: the dashboard's save has already succeeded, and a failure here only delays the list until the next sync. */
    public void sync(Guild guild) {
        try {
            commands.updateGuildApplicationCommands(guild, false).whenComplete((result, error) -> {
                if (error != null) log.warn("Could not update the command list of {} ({})", guild.getName(), guild.getId(), error);
                else log.info("Updated the command list of {} ({}) after a fun command was switched", guild.getName(), guild.getId());
            });
        } catch (RuntimeException e) {
            log.warn("Could not start a command list update for {} ({})", guild.getName(), guild.getId(), e);
        }
    }
}
