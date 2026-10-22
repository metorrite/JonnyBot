package com.younglings.bot.commands.lookup;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.lookup.DndTable;
import com.younglings.bot.lookup.LookupTags;
import com.younglings.bot.lookup.RecurringEventsService;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.AutocompleteMode;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.annotations.AutocompleteHandler;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * {@code /dnd [group:]}: the Distractions and Diversions grouped by how often they can be played, read from the wiki's table. Tag: {@link LookupTags#RUNESCAPE}.
 * Switched on or off per server on the Commands page.
 */
@Command
public class DndCommand {
    static final String GROUPS = "dnd-groups";
    static final List<String> GROUP_NAMES = List.of("Daily", "Weekly", "Monthly", "When available", "Item");
    private static final int MAX_CHARS = 3600;

    private final RecurringEventsService recurring;

    public DndCommand(RecurringEventsService recurring) {
        this.recurring = recurring;
    }

    @JDASlashCommand(name = "dnd", description = "RuneScape 3 Distractions and Diversions by how often they can be played")
    public void onDnd(GuildSlashEvent event, @SlashOption(description = "Only this group (leave out to see them all)", autocomplete = GROUPS) @Nullable String group) {
        String wanted = group == null || group.isBlank() ? null : GROUP_NAMES.stream().filter(g -> g.equalsIgnoreCase(group.strip())).findFirst().orElse(null);
        if (group != null && !group.isBlank() && wanted == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "lookup.isn_t_a_group_choose_daily", group.strip()));
            return;
        }
        event.deferReply().queue();
        Optional<List<DndTable.Entry>> rows = recurring.all();
        event.getHook().sendMessageComponents(card(rows, wanted)).useComponentsV2(true).queue();
    }

    @AutocompleteHandler(value = GROUPS, mode = AutocompleteMode.FUZZY, showUserInput = false)
    public List<String> suggestGroups(CommandAutoCompleteInteractionEvent event) {
        return GROUP_NAMES;
    }

    static Container card(Optional<List<DndTable.Entry>> rows, String group) {
        if (rows.isEmpty()) {
            return Containers.toast(Containers.WARNING, "The wiki isn't answering right now, so the list can't be shown. Try again in a minute.");
        }
        StringBuilder text = new StringBuilder("### Distractions and Diversions\n");
        for (String g : GROUP_NAMES) {
            if (group != null && !g.equals(group)) continue;
            List<DndTable.Entry> entries = rows.get().stream().filter(e -> e.category().equalsIgnoreCase(g)).toList();
            if (entries.isEmpty()) continue;
            StringBuilder section = new StringBuilder("\n**" + heading(g) + "**\n");
            for (DndTable.Entry e : entries) {
                section.append("• ").append(e.name());
                String detail = detail(e);
                if (!detail.isEmpty()) section.append(": ").append(detail);
                section.append('\n');
            }
            if (text.length() + section.length() > MAX_CHARS) {
                text.append("\n…and more. Pick one group to see all of it.\n");
                break;
            }
            text.append(section);
        }
        text.append("-# From the RuneScape Wiki. Resets: /reset");
        return Containers.card(Containers.PRIMARY, TextDisplay.of(text.toString()));
    }

    private static String heading(String group) {
        return switch (group) {
            case "Daily" -> "Daily";
            case "Weekly" -> "Weekly";
            case "Monthly" -> "Monthly";
            case "When available" -> "Comes round by itself";
            default -> "Needs an item";
        };
    }

    private static String detail(DndTable.Entry e) {
        String timing = e.timing();
        String freq = e.frequency();
        if (!timing.isEmpty() && !freq.isEmpty()) return timing + ", " + freq.substring(0, 1).toLowerCase() + freq.substring(1);
        return timing.isEmpty() ? freq : timing;
    }
}
