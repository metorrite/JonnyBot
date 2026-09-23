package com.younglings.bot.commands.coffer;

import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import net.dv8tion.jda.api.entities.User;
import org.jetbrains.annotations.Nullable;

/**
 * Retired in favor of {@link CofferHubCommand}'s single {@code /coffer} entry point with buttons
 * and modals — kept (not deleted) as reference/fallback, but no longer registered. BotCommands
 * validates that every {@code @JDASlashCommand} method's declaring class is {@code @Command}
 * (and throws at startup otherwise), so all the framework annotations are stripped here, not just
 * the class-level one — this is now plain, uncalled Java, not a disabled command.
 */
public class CofferCommand {
    private final CofferService cofferService;

    public CofferCommand(CofferService cofferService) {
        this.cofferService = cofferService;
    }

    public void onSubmit(
            GuildSlashEvent event,
            String donorName,
            String amount
    ) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }
        cofferService.submitDonation(event, donorName, amount);
    }

    public void onDisplay(GuildSlashEvent event) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }
        // CofferService.displayCoffer now takes a ComponentInteraction (it paginates, which needs
        // edit capability a plain slash command event doesn't have) — retired reference code only,
        // not wired to the current service API.
    }

    public void onDisplayLog(GuildSlashEvent event) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }
        // See onDisplay above — CofferService.displayLog has the same signature change.
    }

    public void onTransfer(
            GuildSlashEvent event,
            User recipient,
            String amount
    ) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }
        cofferService.transferCoffer(event, null, recipient, amount);
    }

    public void onGiveaway(
            GuildSlashEvent event,
            User recipient,
            String amount,
            @Nullable User holder,
            @Nullable String description
    ) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }
        cofferService.recordGiveaway(event, holder, recipient, amount, description);
    }
}
