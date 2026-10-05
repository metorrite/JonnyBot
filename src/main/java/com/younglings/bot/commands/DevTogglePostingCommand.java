package com.younglings.bot.commands;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.tracking.TrackingPostingToggle;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.annotations.Test;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;

/**
 * Dev-only: flips {@link TrackingPostingToggle} off (or back on) so a locally-running JonnyBot Dev can
 * poll and classify exactly like normal without actually posting to the shared tracking channels —
 * running dev alongside the real production bot otherwise means every real drop/join/admin-log entry
 * gets posted twice, once by each. {@code @Test} (with {@link CommandScope#GUILD}) keeps this out of
 * production's command list entirely, and {@link BotConfig#getLiveEnvironment()} is checked again at
 * runtime as a second layer, same pattern as {@code DevClearCommandsCommand}.
 */
@Command
public class DevTogglePostingCommand {
    private final BotConfig botConfig;
    private final TrackingPostingToggle postingToggle;

    public DevTogglePostingCommand(BotConfig botConfig, TrackingPostingToggle postingToggle) {
        this.botConfig = botConfig;
        this.postingToggle = postingToggle;
    }

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @Test({})
    @JDASlashCommand(name = "devtoggleposting", description = "[Dev only] Toggles whether this bot actually posts tracking entries (starts off on dev)")
    public void onDevTogglePosting(GuildSlashEvent event) {
        if (botConfig.getLiveEnvironment()) {
            Containers.replyEphemeral(event, Containers.WARNING, "This command is dev-only.");
            return;
        }

        boolean nowEnabled = postingToggle.toggle();
        Containers.replyEphemeral(event, nowEnabled ? Containers.SUCCESS : Containers.WARNING,
                nowEnabled
                        ? "Tracking posts are back **on** — this instance will post to the real channels again."
                        : "Tracking posts are now **off** for this instance — polling/classification/DB writes still run normally, nothing actually gets sent to Discord.");
    }
}
