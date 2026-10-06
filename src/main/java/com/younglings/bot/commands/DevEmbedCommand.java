package com.younglings.bot.commands;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;

/**
 * Dev-only: opens a paste box, and {@link DevEmbedListener} posts whatever's pasted as an embed in the
 * channel the command was used in — converting plain bold-and-bullet text into headings and lists first
 * (see {@link com.younglings.bot.announcement.PostTextConverter}), with the Embedded Posts tags
 * ({@code ~<LS>~}, buttons, ...) working as they do there. {@code @Test} (with {@link CommandScope#GUILD})
 * keeps it out of production's command list entirely, and {@link BotConfig#getLiveEnvironment()} is checked
 * again at runtime as a second layer, same pattern as {@code DevTogglePostingCommand}.
 */
@Command
public class DevEmbedCommand {
    private final BotConfig botConfig;

    public DevEmbedCommand(BotConfig botConfig) {
        this.botConfig = botConfig;
    }

    @JDASlashCommand(name = "dev", subcommand = "embed", description = "Paste text into a form and the bot posts it here as an embed")
    public void onDevEmbed(GuildSlashEvent event) {
        if (botConfig.getLiveEnvironment()) {
            Containers.replyEphemeral(event, Containers.WARNING, "This command is dev-only.");
            return;
        }
        event.replyModal(DevEmbedListener.buildModal()).queue();
    }
}
