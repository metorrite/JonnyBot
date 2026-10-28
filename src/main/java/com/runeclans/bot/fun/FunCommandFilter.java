package com.runeclans.bot.fun;

import com.runeclans.bot.hub.HubCommand;
import com.runeclans.bot.hub.HubService;
import io.github.freya022.botcommands.api.commands.CommandPath;
import io.github.freya022.botcommands.api.commands.application.CommandDeclarationFilter;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Decides, per server, whether a fun command is registered with Discord at all: only while the server has it switched on in the Hub.
 * That is what makes "off" mean "not in the command list" (a global command cannot be hidden per server; a guild command can simply not
 * be declared there). BotCommands asks this whenever it syncs a server's commands, at startup and when {@link FunCommandSync} runs.
 */
@BService
public class FunCommandFilter implements CommandDeclarationFilter {
    private final HubService hub;

    public FunCommandFilter(HubService hub) {
        this.hub = hub;
    }

    @Override
    public boolean filter(Guild guild, CommandPath path, @Nullable String commandId) {
        Optional<HubCommand> command = HubCommand.ofSlashName(path.getName());
        if (command.isEmpty()) return false; // a fun command with no Hub entry would be unmanageable: leave it out
        return hub.get(guild.getIdLong(), command.get()).enabled();
    }
}
