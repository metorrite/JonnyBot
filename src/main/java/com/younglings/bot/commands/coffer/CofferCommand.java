package com.younglings.bot.commands.coffer;

import com.younglings.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.annotations.Filter;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.entities.User;
import org.jetbrains.annotations.Nullable;

@Command
public class CofferCommand {
    private final CofferService cofferService;

    public CofferCommand(CofferService cofferService) {
        this.cofferService = cofferService;
    }

    @TopLevelSlashCommandData(description = "Manage the clan coffer")
    @JDASlashCommand(name = "coffer", subcommand = "submit", description = "Log a donation to the clan coffer")
    public void onSubmit(
            GuildSlashEvent event,
            @SlashOption(description = "In-game name of the player who donated") String donorName,
            @SlashOption(description = "Amount donated — e.g. 150M, 500K, 1.5B, 150000, 150_000") String amount
    ) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }
        cofferService.submitDonation(event, donorName, amount);
    }

    @JDASlashCommand(name = "coffer", subcommand = "display", description = "Show the clan coffer total and holder breakdown")
    public void onDisplay(GuildSlashEvent event) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }
        cofferService.displayCoffer(event);
    }

    @JDASlashCommand(name = "coffer", subcommand = "log", description = "Show the last 25 donations to the clan coffer")
    public void onDisplayLog(GuildSlashEvent event) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }
        cofferService.displayLog(event);
    }

    @Filter(AdminRoleFilter.class)
    @JDASlashCommand(name = "coffer", subcommand = "transfer", description = "Transfer coffer money you are holding to another holder")
    public void onTransfer(
            GuildSlashEvent event,
            @SlashOption(description = "Coffer holder to transfer to") User recipient,
            @SlashOption(description = "Amount to transfer — e.g. 500M, 1B") String amount
    ) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }
        cofferService.transferCoffer(event, recipient, amount);
    }

    @Filter(AdminRoleFilter.class)
    @JDASlashCommand(name = "coffer", subcommand = "giveaway", description = "Record a giveaway prize paid from the clan coffer")
    public void onGiveaway(
            GuildSlashEvent event,
            @SlashOption(description = "Discord user who won the prize") User recipient,
            @SlashOption(description = "Amount given away — e.g. 300M") String amount,
            @SlashOption(description = "Holder paying out the prize (defaults to you)") @Nullable User holder,
            @SlashOption(description = "What the prize was given for") @Nullable String description
    ) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }
        cofferService.recordGiveaway(event, holder, recipient, amount, description);
    }
}
