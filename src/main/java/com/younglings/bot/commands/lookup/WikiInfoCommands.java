package com.younglings.bot.commands.lookup;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.lookup.LookupTags;
import com.younglings.bot.lookup.WikiInfoService;
import com.younglings.bot.lookup.WikiInfoService.Kind;
import com.younglings.bot.lookup.WikiInfoService.Summary;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.AutocompleteMode;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.annotations.AutocompleteHandler;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /boss name:} and {@code /quest name:}: a short summary from the RuneScape Wiki with a few facts and a link to the page's strategy or quick guide.
 * Tag: {@link LookupTags#RUNESCAPE}. Each is switched on or off per server on the Commands page.
 */
@Command
public class WikiInfoCommands {
    static final String BOSSES = "wiki-bosses";
    static final String QUESTS = "wiki-quests";

    private final WikiInfoService wiki;

    public WikiInfoCommands(WikiInfoService wiki) {
        this.wiki = wiki;
    }

    @JDASlashCommand(name = "boss", description = "RuneScape 3 boss: a short summary from the wiki, with its key facts and strategy link")
    public void onBoss(GuildSlashEvent event, @SlashOption(description = "The boss (suggestions come from the wiki)", autocomplete = BOSSES) String name) {
        answer(event, Kind.BOSS, name);
    }

    @JDASlashCommand(name = "quest", description = "RuneScape 3 quest: a short summary from the wiki, with its key facts and quick guide link")
    public void onQuest(GuildSlashEvent event, @SlashOption(description = "The quest (suggestions come from the wiki)", autocomplete = QUESTS) String name) {
        answer(event, Kind.QUEST, name);
    }

    private void answer(GuildSlashEvent event, Kind kind, String name) {
        event.deferReply().queue();
        var outcome = wiki.lookup(kind, name);
        switch (outcome.status()) {
            case FOUND -> event.getHook().sendMessageComponents(card(outcome.summary())).useComponentsV2(true).queue();
            case NOT_FOUND -> event.getHook().sendMessageComponents(Containers.toast(Containers.WARNING,
                    "I don't know a " + (kind == Kind.BOSS ? "boss" : "quest") + " called **" + name.strip() + "**.",
                    "-# Start typing the name and pick one of the suggestions.")).useComponentsV2(true).queue();
            case UNAVAILABLE -> event.getHook().sendMessageComponents(Containers.toast(Containers.WARNING,
                    "The wiki isn't answering right now. Try again in a minute.")).useComponentsV2(true).queue();
        }
    }

    @AutocompleteHandler(value = BOSSES, mode = AutocompleteMode.FUZZY, showUserInput = false)
    public List<String> suggestBosses(CommandAutoCompleteInteractionEvent event) {
        return wiki.names(Kind.BOSS);
    }

    @AutocompleteHandler(value = QUESTS, mode = AutocompleteMode.FUZZY, showUserInput = false)
    public List<String> suggestQuests(CommandAutoCompleteInteractionEvent event) {
        return wiki.names(Kind.QUEST);
    }

    static Container card(Summary s) {
        StringBuilder text = new StringBuilder("### [").append(s.title()).append("](").append(s.url()).append(")\n");
        for (Map.Entry<String, String> fact : s.facts().entrySet()) text.append("**").append(fact.getKey()).append(":** ").append(fact.getValue()).append("  ·  ");
        if (!s.facts().isEmpty()) text.setLength(text.length() - 5);
        if (!s.facts().isEmpty()) text.append('\n');
        text.append('\n').append(s.extract()).append("\n-# From the RuneScape Wiki");

        List<ContainerChildComponent> children = new ArrayList<>();
        TextDisplay body = TextDisplay.of(text.toString());
        children.add(s.thumbnail() == null ? body : Section.of(Thumbnail.fromUrl(s.thumbnail()), body));
        if (s.guideUrl() != null) children.add(ActionRow.of(Button.link(s.guideUrl(), s.guideLabel())));
        return Containers.card(Containers.PRIMARY, children);
    }
}
