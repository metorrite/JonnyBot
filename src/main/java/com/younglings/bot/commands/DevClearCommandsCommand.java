package com.younglings.bot.commands;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.annotations.Test;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.entities.Guild;

import java.util.List;

/**
 * Dev-only: clears every guild-scoped command this bot has registered to {@code GUILD_ID}. Run
 * this right before stopping the dev bot so it doesn't leave stray commands (including every
 * {@code @Test} one) sitting on the shared dev/prod Discord server — Discord has no concept of
 * "the bot that registered this went offline," commands persist until something explicitly clears
 * them.
 * <p>
 * Replaces an earlier shutdown-hook-based approach: JVM shutdown hooks only run on a graceful
 * exit, and Windows has no reliable way to request that from outside the process (a plain
 * {@code taskkill} without {@code /F} against this bot's own process is refused — "can only be
 * terminated forcefully"). A command run yourself, while the bot is still fully up, sidesteps
 * that uncertainty entirely instead of hoping the right signal reaches the process.
 */
@Command
public class DevClearCommandsCommand {
    private final BotConfig botConfig;

    public DevClearCommandsCommand(BotConfig botConfig) {
        this.botConfig = botConfig;
    }

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @Test({})
    @JDASlashCommand(name = "devclearcommands", description = "[Dev only] Clears every dev-registered command from this server")
    public void onDevClearCommands(GuildSlashEvent event) {
        if (botConfig.getLiveEnvironment()) {
            Containers.replyEphemeral(event, Containers.WARNING, "This command is dev-only.");
            return;
        }

        Guild guild = event.getGuild();
        if (guild == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "This command can only be used in a server.");
            return;
        }

        event.deferReply(true).queue();

        guild.updateCommands().queue(
                commands -> event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                                "Cleared every guild-scoped command from this server. Safe to stop the dev bot now — " +
                                        "run it again whenever to re-register them.")))
                        .useComponentsV2(true).queue(),
                failure -> event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.DANGER,
                                "Failed to clear commands: " + failure.getMessage())))
                        .useComponentsV2(true).queue()
        );
    }
}
