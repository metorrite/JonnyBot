package com.runeclans.bot.commands.lookup;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.lookup.GePriceService;
import com.runeclans.bot.lookup.GePriceService.GePrice;
import com.runeclans.bot.lookup.GePriceService.Trend;
import com.runeclans.bot.lookup.LookupTags;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.AutocompleteMode;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.annotations.AutocompleteHandler;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * {@code /price item:}: the Grand Exchange price of a RuneScape 3 item with its 7 and 30 day change and a link to the wiki. Tag: {@link LookupTags#RUNESCAPE}.
 * Switched on or off per server on the dashboard's Commands page like any hub command.
 */
@Command
public class PriceCommand {
    static final String ITEMS = "ge-items";

    private final GePriceService prices;

    public PriceCommand(GePriceService prices) {
        this.prices = prices;
    }

    @JDASlashCommand(name = "price", description = "RuneScape 3 Grand Exchange price of an item, with its 7 and 30 day change")
    public void onPrice(GuildSlashEvent event, @SlashOption(description = "The item (suggestions come from the wiki)", autocomplete = ITEMS) String item) {
        event.deferReply().queue();
        GePriceService.Outcome outcome = prices.lookup(item);
        switch (outcome.status()) {
            case FOUND -> event.getHook().sendMessageComponents(card(outcome.price())).useComponentsV2(true).queue();
            case NOT_FOUND -> event.getHook().sendMessageComponents(Containers.toast(Containers.WARNING,
                    "I can't find **" + item.strip() + "** on the Grand Exchange.",
                    "-# Start typing the name and pick one of the suggestions. Items that can't be traded have no price.")).useComponentsV2(true).queue();
            case UNAVAILABLE -> event.getHook().sendMessageComponents(Containers.toast(Containers.WARNING,
                    "The price service isn't answering right now. Try again in a minute.")).useComponentsV2(true).queue();
        }
    }

    @AutocompleteHandler(value = ITEMS, mode = AutocompleteMode.CONTINUITY, showUserInput = false)
    public List<String> suggestItems(CommandAutoCompleteInteractionEvent event) {
        return prices.suggest(event.getFocusedOption().getValue(), 25);
    }

    static Container card(GePrice p) {
        StringBuilder text = new StringBuilder();
        text.append("### [").append(p.name()).append("](").append(p.wikiUrl()).append(")\n");
        text.append("**").append(coins(p.price())).append(" gp**");
        if (p.volume() > 0) text.append("  ·  ").append(number(p.volume())).append(" traded");
        text.append('\n');
        text.append(trendLine(p.week())).append('\n').append(trendLine(p.month()));
        if (p.at() != null) text.append("\n-# Grand Exchange price as of <t:").append(p.at().getEpochSecond()).append(":R>, from the RuneScape Wiki");
        return Containers.card(Containers.PRIMARY, TextDisplay.of(text.toString()));
    }

    static String trendLine(Trend t) {
        String label = t.days() + " days";
        if (t.percent() == null) return label + ": not enough history";
        double pct = t.percent();
        String arrow = Math.abs(pct) < 0.05 ? "➡️" : pct > 0 ? "📈" : "📉";
        return arrow + " " + label + ": " + String.format(Locale.ROOT, "%+.1f%%", pct) + " (was " + coins(t.thenPrice()) + " gp)";
    }

    static String coins(long gp) {
        return number(gp);
    }

    private static String number(long n) {
        return NumberFormat.getIntegerInstance(Locale.UK).format(n);
    }
}
