package com.younglings.bot.commands;

import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;

@Command
public class SlashPing {

    @JDASlashCommand(name = "ping", description = "Replies with pong")
    public void onPing(GuildSlashEvent event) {
        event.reply("Pong!").setEphemeral(true).queue();
    }
}
