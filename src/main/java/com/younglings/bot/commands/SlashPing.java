package com.younglings.bot.commands;

import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.annotations.Test;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;

@Command
public class SlashPing {

    /**
     * Test-only (see {@link Test}) — only pushed to the guilds in
     * {@code BApplicationConfig.testGuildIds}, which {@code Main} populates with
     * {@link com.younglings.bot.config.BotConfig#getGuildId()} outside of production and leaves
     * empty in production. So this registers nowhere at all when {@code LIVE_ENV} is true, instead
     * of relying on remembering to delete a debug command before shipping.
     */
    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @Test({})
    @JDASlashCommand(name = "ping", description = "Replies with pong")
    public void onPing(GuildSlashEvent event) {
        event.reply("Pong!").setEphemeral(true).queue();
    }
}
