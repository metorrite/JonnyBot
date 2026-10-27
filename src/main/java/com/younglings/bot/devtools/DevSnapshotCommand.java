package com.younglings.bot.devtools;

import com.younglings.bot.discord.Shards;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.notice.BotOwners;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.FileUpload;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /dev snapshot [focus] [guild]}: the same snapshot the dashboard's Snapshots page takes, for the bot's owner. The {@code /dev}
 * group exists only on test servers (see {@code SlashPing}), so on the live bot the dashboard is the way in; the dashboard route works
 * everywhere.
 */
@Command
public class DevSnapshotCommand {
    private static final Logger log = LoggerFactory.getLogger(DevSnapshotCommand.class);

    private final DevSnapshotService service;
    private final BotOwners owners;

    public DevSnapshotCommand(DevSnapshotService service, BotOwners owners) {
        this.service = service;
        this.owners = owners;
    }

    @JDASlashCommand(name = "dev", subcommand = "snapshot", description = "Bundles the bot's state into a zip with an id for a bug report (bot owner only)")
    public void onSnapshot(GuildSlashEvent event,
                           @SlashOption(description = "Narrow it to event:<id>, ticket:<id> or member:<Discord id>") @Nullable String focus,
                           @SlashOption(description = "Server ID to snapshot (default: this one)") @Nullable String guild) {
        if (!owners.isOwner(event.getJDA(), event.getUser().getIdLong())) {
            Containers.replyEphemeral(event, Containers.WARNING, "Only the bot's owner can take snapshots.");
            return;
        }
        Guild target = event.getGuild();
        if (guild != null && !guild.isBlank()) {
            try {
                target = Shards.guild(event.getJDA(), guild.strip());
            } catch (NumberFormatException e) {
                target = null;
            }
            if (target == null) {
                Containers.replyEphemeral(event, Containers.WARNING, "The bot isn't in a server with that ID.");
                return;
            }
        }

        event.deferReply(true).queue();
        Guild finalTarget = target;
        try {
            var result = service.create(finalTarget, event.getUser().getIdLong(), event.getMember().getEffectiveName(), focus == null ? "" : focus, "");
            event.getHook().sendMessage("Snapshot **" + result.id() + "** taken of " + finalTarget.getName()
                            + (result.focus().isEmpty() ? "" : " (focus " + result.focus() + ")") + ". Put that id in the bug report.")
                    .setFiles(FileUpload.fromData(result.zip(), result.id() + ".zip")).setEphemeral(true).queue();
        } catch (DevSnapshotService.SnapshotException e) {
            event.getHook().sendMessage(e.getMessage()).setEphemeral(true).queue();
        } catch (Exception e) {
            log.error("Dev snapshot failed", e);
            event.getHook().sendMessage("The snapshot failed; check the bot's logs.").setEphemeral(true).queue();
        }
    }
}
