package com.younglings.bot.commands;

import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;

@Command
public class SlashPing {

    @JDASlashCommand(name = "ping", description = "Replies with pong")
    public void onPing(GuildSlashEvent event) {
        Containers.replyEphemeral(event, Containers.SUCCESS, "Pong!");
    }
}
