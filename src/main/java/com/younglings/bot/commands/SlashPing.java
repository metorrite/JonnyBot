package com.younglings.bot.commands;

import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.annotations.Test;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;

/** Dev-only connectivity check — {@code @Test} keeps it off production, same as {@code /dev signups}. */
@Command
public class SlashPing {

    // The one place the /dev group is declared as guild-scoped and test-only: @Test means it is only ever pushed to the
    // test guild(s), so none of /dev exists on the live bot. Every subcommand also refuses to run if it somehow did.
    @TopLevelSlashCommandData(scope = CommandScope.GUILD, description = "Developer tools for testing JonnyBot (never on the live bot)")
    @Test({})
    @JDASlashCommand(name = "dev", subcommand = "ping", description = "Replies with pong")
    public void onPing(GuildSlashEvent event) {
        Containers.replyEphemeral(event, Containers.SUCCESS, "Pong!");
    }
}
