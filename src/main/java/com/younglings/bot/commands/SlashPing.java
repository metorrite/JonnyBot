package com.younglings.bot.commands;

import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.annotations.Test;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;

/** Dev-only connectivity check — {@code @Test} keeps it off production, same as {@code /devsignups}. */
@Command
public class SlashPing {

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @Test({})
    @JDASlashCommand(name = "ping", description = "[Dev only] Replies with pong")
    public void onPing(GuildSlashEvent event) {
        Containers.replyEphemeral(event, Containers.SUCCESS, "Pong!");
    }
}
