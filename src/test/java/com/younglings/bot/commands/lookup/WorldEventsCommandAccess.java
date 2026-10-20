package com.younglings.bot.commands.lookup;

import com.younglings.bot.lookup.DndTable;
import com.younglings.bot.lookup.VosService;
import net.dv8tion.jda.api.components.container.Container;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Lets a test in another package reach the command's package-private card builder. */
public final class WorldEventsCommandAccess {
    private WorldEventsCommandAccess() {}

    public static Container card(Optional<VosService.Vos> vos, Optional<List<DndTable.Entry>> events, Instant now) {
        return WorldEventsCommand.card(vos, events, now);
    }
}
