package com.runeclans.bot.commands.tools;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Messages;
import com.runeclans.bot.tools.LootSplit;
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
    private final Messages messages;

    public SplitCommand(Messages messages) {
        this.messages = messages;
    }

    @JDASlashCommand(name = "split", description = "Split a drop between a group, with an optional host or clan fee")
    public void onSplit(
            GuildSlashEvent event,
            @SlashOption(description = "The total, e.g. 1.5b, 250m or 12,500k") String total,
            @SlashOption(description = "How many people share it") int people,
            @SlashOption(description = "A fee off the top in percent, e.g. 5 (optional)") @Nullable Double fee) {
        long guildId = event.getGuild().getIdLong();
        var amount = LootSplit.parseAmount(total);
        if (amount.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING, messages.text(guildId, "split.amount.unreadable"), messages.text(guildId, "split.amount.hint"));
            return;
        }
        if (people < 1 || people > LootSplit.MAX_PEOPLE) {
            Containers.replyEphemeral(event, Containers.WARNING, messages.text(guildId, "split.people.range", LootSplit.MAX_PEOPLE));
            return;
        }
        double feePercent = fee == null ? 0 : fee;
        if (Double.isNaN(feePercent) || feePercent < 0 || feePercent > 100) {
            Containers.replyEphemeral(event, Containers.WARNING, messages.text(guildId, "split.fee.range"));
            return;
        }

        var r = LootSplit.split(amount.get(), people, feePercent);
        var lines = new java.util.ArrayList<String>();
        lines.add(messages.text(guildId, "split.title"));
        lines.add(messages.text(guildId, "split.total", LootSplit.format(r.total())));
        if (r.fee() > 0) {
            lines.add(messages.text(guildId, "split.fee", trim(feePercent), LootSplit.format(r.fee())));
            lines.add(messages.text(guildId, "split.pot", LootSplit.format(r.pot())));
        }
        lines.add(messages.text(guildId, "split.each", r.people(), LootSplit.format(r.share())));
        if (r.remainder() > 0) {
            lines.add(messages.text(guildId, r.remainder() == 1 ? "split.leftover.one" : "split.leftover.other", r.remainder()));
        }
        Containers.reply(event, Containers.SUCCESS, false, lines.toArray(String[]::new));
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
