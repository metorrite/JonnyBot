package com.younglings.bot.commands.lookup;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.lookup.LookupTags;
import com.younglings.bot.lookup.ResetTimes;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

import java.time.Instant;

/**
 * {@code /reset}: countdowns to the daily, weekly and monthly RuneScape 3 resets (00:00 UTC; weekly on Wednesday, monthly on the 1st). Worked out locally, so it
 * never fails. Tag: {@link LookupTags#RUNESCAPE}. Switched on or off per server on the Commands page; reminders at each reset are set on the RuneScape timers page.
 */
@Command
public class ResetCommand {
    @JDASlashCommand(name = "reset", description = "RuneScape 3: countdowns to the daily, weekly and monthly resets")
    public void onReset(GuildSlashEvent event) {
        event.replyComponents(card(Instant.now())).useComponentsV2(true).queue();
    }

    static Container card(Instant now) {
        String text = "### Resets\n"
                + "**Daily:** <t:" + ResetTimes.nextDaily(now).getEpochSecond() + ":R> (<t:" + ResetTimes.nextDaily(now).getEpochSecond() + ":t> your time)\n"
                + "**Weekly:** <t:" + ResetTimes.nextWeekly(now).getEpochSecond() + ":R> (<t:" + ResetTimes.nextWeekly(now).getEpochSecond() + ":F>)\n"
                + "**Monthly:** <t:" + ResetTimes.nextMonthly(now).getEpochSecond() + ":R> (<t:" + ResetTimes.nextMonthly(now).getEpochSecond() + ":D>)\n"
                + "-# Daily at 00:00 UTC, weekly on Wednesdays, monthly on the 1st. Times show in your own time zone.";
        return Containers.card(Containers.PRIMARY, TextDisplay.of(text));
    }
}
