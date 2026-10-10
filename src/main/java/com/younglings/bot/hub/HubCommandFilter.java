package com.younglings.bot.hub;

import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.commands.application.ApplicationCommandFilter;
import io.github.freya022.botcommands.api.commands.application.ApplicationCommandInfo;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Applies a server's Hub settings (see {@link HubService}) to the slash commands that have a card on the dashboard's Hub, before the
 * command runs. Every other command passes straight through, and a Hub command whose server has saved nothing behaves exactly as
 * it always has.
 */
@BService
@NullMarked
public class HubCommandFilter implements ApplicationCommandFilter {
    private final HubService hub;

    public HubCommandFilter(HubService hub) {
        this.hub = hub;
    }

    @Override
    public boolean getGlobal() {
        return true;
    }

    @Override
    public @Nullable String check(GenericCommandInteractionEvent event, ApplicationCommandInfo commandInfo) {
        Optional<HubCommand> command = HubCommand.ofSlashName(event.getName());
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (command.isEmpty() || guild == null || member == null) return null; // not a Hub command, or the command itself explains it needs a server

        Long parentId = event.getChannel() instanceof ThreadChannel thread ? thread.getParentChannel().getIdLong() : null;
        HubService.Decision decision = hub.check(guild, member, command.get(), event.getChannel().getIdLong(), parentId);
        if (decision.allowed()) return null;

        Containers.replyEphemeral(event, Containers.WARNING, decision.message());
        return "Refused by the server's Hub settings";
    }
}
