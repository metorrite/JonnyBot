package com.runeclans.bot.commands.lookup;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.lookup.DndTable;
import com.runeclans.bot.lookup.LookupTags;
import com.runeclans.bot.lookup.RecurringEventsService;
import com.runeclans.bot.lookup.VosService;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code /worldevents}: what is boosted in Prifddinas right now (Voice of Seren) and which events come round by themselves, with how often. The first comes from
 * the Weird Gloop API, the second from the RuneScape Wiki's Distractions and Diversions table (so it follows the game's changes instead of a list kept here).
 * Tag: {@link LookupTags#RUNESCAPE}. Switched on or off per server on the Commands page; the optional hourly reminder is set on the dashboard's RuneScape timers page.
 */
@Command
public class WorldEventsCommand {
    private final VosService vos;
    private final RecurringEventsService recurring;

    public WorldEventsCommand(VosService vos, RecurringEventsService recurring) {
        this.vos = vos;
        this.recurring = recurring;
    }

    @JDASlashCommand(name = "worldevents", description = "RuneScape 3: the Voice of Seren now, and the events that come round by themselves")
    public void onWorldEvents(GuildSlashEvent event) {
        event.deferReply().queue();
        Container card = card(vos.fetch(), recurring.category("When available"), Instant.now());
        event.getHook().sendMessageComponents(card).useComponentsV2(true).queue();
    }

    static Container card(Optional<VosService.Vos> vos, Optional<List<DndTable.Entry>> events, Instant now) {
        StringBuilder text = new StringBuilder("### World events\n");
        if (vos.isPresent()) {
            VosService.Vos v = vos.get();
            text.append("**Voice of Seren:** ").append(v.district1()).append(" and ").append(v.district2()).append('\n');
            if (v.current(now)) text.append("-# Changes <t:").append(VosService.nextChange(now).getEpochSecond()).append(":R>\n");
            else text.append("-# Last reported <t:").append(v.since().getEpochSecond()).append(":R>, so this may be out of date\n");
        } else {
            text.append("**Voice of Seren:** the service isn't answering right now.\n");
        }
        text.append("\n**Comes round by itself**\n");
        if (events.isPresent()) {
            for (DndTable.Entry e : events.get()) {
                String timing = e.timing().isBlank() ? "when it appears" : e.timing().substring(0, 1).toLowerCase() + e.timing().substring(1);
                text.append("• ").append(e.name()).append(": ").append(timing).append('\n');
            }
        } else {
            text.append("The wiki isn't answering right now, so the list can't be shown.\n");
        }
        text.append("-# Voice of Seren from Weird Gloop, the rest from the RuneScape Wiki.");
        return Containers.card(Containers.PRIMARY, TextDisplay.of(text.toString()));
    }
}
