package com.younglings.bot.commands.lookup;

import com.younglings.bot.lookup.DndTable;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Lets tests in another package reach the commands' package-private card builders and read a card's text. */
public final class ResetsDndAccess {
    private ResetsDndAccess() {}

    public static Container dnd(Optional<List<DndTable.Entry>> rows, String group) {
        return DndCommand.card(rows, group);
    }

    public static Container reset(Instant now) {
        return ResetCommand.card(now);
    }

    public static String text(Container c) {
        StringBuilder out = new StringBuilder();
        c.getComponents().forEach(x -> {
            if (x instanceof TextDisplay t) out.append(t.getContent());
        });
        return out.toString();
    }
}
