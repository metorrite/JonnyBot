package com.younglings.bot.commands.tools;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.tools.LootSplit;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /split total people [fee]}: divides a drop between a group. Amounts can be written as 1.5b, 250m or 12,500k. The reply
 * is public so the whole group sees the same numbers.
 */
@Command
public class SplitCommand {
    @JDASlashCommand(name = "split", description = "Split a drop between a group, with an optional host or clan fee")
    public void onSplit(
            GuildSlashEvent event,
            @SlashOption(description = "The total, e.g. 1.5b, 250m or 12,500k") String total,
            @SlashOption(description = "How many people share it") int people,
            @SlashOption(description = "A fee off the top in percent, e.g. 5 (optional)") @Nullable Double fee) {
        var amount = LootSplit.parseAmount(total);
        if (amount.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING, "I couldn't read that amount.", "-# Try something like 1.5b, 250m, 12,500k or 1250000.");
            return;
        }
        if (people < 1 || people > LootSplit.MAX_PEOPLE) {
            Containers.replyEphemeral(event, Containers.WARNING, "Choose between 1 and " + LootSplit.MAX_PEOPLE + " people.");
            return;
        }
        double feePercent = fee == null ? 0 : fee;
        if (Double.isNaN(feePercent) || feePercent < 0 || feePercent > 100) {
            Containers.replyEphemeral(event, Containers.WARNING, "The fee is a percentage from 0 to 100.");
            return;
        }

        var r = LootSplit.split(amount.get(), people, feePercent);
        var lines = new java.util.ArrayList<String>();
        lines.add("### Loot split");
        lines.add("**Total:** " + LootSplit.format(r.total()));
        if (r.fee() > 0) {
            lines.add("**Fee (" + trim(feePercent) + "%):** " + LootSplit.format(r.fee()));
            lines.add("**To share:** " + LootSplit.format(r.pot()));
        }
        lines.add("**Each of " + r.people() + ":** " + LootSplit.format(r.share()));
        if (r.remainder() > 0) {
            lines.add("-# " + r.remainder() + " coin" + (r.remainder() == 1 ? "" : "s") + " left over");
        }
        Containers.reply(event, Containers.SUCCESS, false, lines.toArray(String[]::new));
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
