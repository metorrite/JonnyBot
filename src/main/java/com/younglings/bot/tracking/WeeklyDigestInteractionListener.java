package com.younglings.bot.tracking;

import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Weekly Citadel Report's "Spin Wheel" button — picks a random name from that same report's
 * visited-and-capped list. Open to anyone in the channel (not admin-gated) since it's a fun
 * engagement feature riding on a public report, not a configuration action.
 */
@BService
public class WeeklyDigestInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(WeeklyDigestInteractionListener.class);
    private static final String PREFIX = "weekly_spin_wheel:";

    private final WeeklyDigestService weeklyDigestService;

    public WeeklyDigestInteractionListener(WeeklyDigestService weeklyDigestService) {
        this.weeklyDigestService = weeklyDigestService;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(PREFIX)) return;

        Guild guild = event.getGuild();
        if (guild == null) return;

        try {
            // The button's customId only carries the window's end date — the report and the button
            // are always built from the same Wed-to-Wed window, so re-deriving it here (rather than
            // encoding the full range) keeps the id short and avoids two sources of truth for it.
            LocalDate windowEndDate = LocalDate.parse(id.substring(PREFIX.length()));
            OffsetDateTime windowEnd = windowEndDate.atTime(LocalTime.MIDNIGHT).atOffset(ZoneOffset.UTC);
            OffsetDateTime windowStart = windowEnd.minusDays(7).plusMinutes(1);

            List<String> eligible = weeklyDigestService.getVisitedAndCappedRsns(guild.getIdLong(), windowStart, windowEnd);
            if (eligible.isEmpty()) {
                Containers.replyEphemeral(event, Containers.WARNING, "Nobody both visited and capped that week — nothing to spin for.");
                return;
            }

            String winner = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
            event.reply("🎡 The wheel landed on **" + winner + "**!").setSuppressedNotifications(true).queue();
        } catch (Exception e) {
            log.error("Unhandled exception in weekly digest spin-wheel interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }
}
