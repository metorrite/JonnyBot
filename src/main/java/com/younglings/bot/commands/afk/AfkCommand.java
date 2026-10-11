package com.younglings.bot.commands.afk;

import com.younglings.bot.afk.AfkService;
import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /afk [reason]}: says you are away. Anyone who mentions you is told, and you are taken off the list the moment you next speak.
 * Running it again changes the reason.
 */
@Command
public class AfkCommand {
    private final AfkService service;

    public AfkCommand(AfkService service) {
        this.service = service;
    }

    @JDASlashCommand(name = "afk", description = "Mark yourself away: people who mention you are told, and it clears when you next speak")
    public void onAfk(GuildSlashEvent event, @SlashOption(description = "Why you're away (optional)") @Nullable String reason) {
        var afk = service.set(event.getGuild().getIdLong(), event.getUser().getIdLong(), reason);
        Containers.replyEphemeral(event, Containers.SUCCESS, "You're marked away" + (afk.reason().isBlank() ? "." : ": " + afk.reason()),
                "-# It clears the next time you send a message.");
    }
}
