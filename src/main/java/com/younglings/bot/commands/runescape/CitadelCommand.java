package com.younglings.bot.commands.runescape;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.tracking.WeeklyDigestService;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * An on-demand look at Citadel participation: how many clan members have capped and visited, and who.
 * Defaults to the current week so far (since the Wednesday 00:00 UTC reset); {@code last_week} shows
 * the most recently completed week instead — the same one the weekly Citadel Report posts. Always
 * ephemeral, and built from the same data as the weekly report so the two can't disagree.
 */
@Command
public class CitadelCommand {
    private final WeeklyDigestService weeklyDigestService;

    public CitadelCommand(WeeklyDigestService weeklyDigestService) {
        this.weeklyDigestService = weeklyDigestService;
    }

    @JDASlashCommand(name = "citadel", description = "How many clan members have capped and visited the Citadel, and who")
    public void onCitadel(GuildSlashEvent event,
                          @SlashOption(description = "Show the last completed week instead of this week so far (default: no)") @Nullable Boolean lastWeek) {
        if (event.getGuild() == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "This command can only be used in a server.");
            return;
        }

        event.replyComponents(List.of(weeklyDigestService.buildWeekSummary(event.getGuild().getIdLong(), Boolean.TRUE.equals(lastWeek))))
                .useComponentsV2(true).setEphemeral(true).queue();
    }
}
