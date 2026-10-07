package com.younglings.bot.commands.combat;

import com.younglings.bot.combat.CombatAchievementModels.Achievement;
import com.younglings.bot.combat.CombatAchievementRepository;
import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.AutocompleteMode;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.annotations.AutocompleteHandler;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;

import java.util.List;

/**
 * {@code /ca}: look up a Combat Mastery achievement. Start typing a name and it is suggested; the answer is the achievement's card (see
 * {@link CombatAchievementView}), posted in the channel so it can be shown to whoever is asking, such as in a ticket. Reads the local copy of the
 * wiki's data, so it never calls the wiki.
 */
@Command
public class CombatAchievementCommand {
    static final String NAMES = "ca-achievement-names";
    private static final int SUGGESTIONS_SHOWN = 8;

    private final CombatAchievementRepository repository;

    public CombatAchievementCommand(CombatAchievementRepository repository) {
        this.repository = repository;
    }

    @JDASlashCommand(name = "ca", description = "Look up a Combat Mastery achievement: its tier, scores, boss, and the wiki's tips")
    public void onCa(GuildSlashEvent event,
                     @SlashOption(description = "The achievement's name (start typing to see suggestions)", autocomplete = NAMES) String achievement) {
        String wanted = achievement.strip();
        var exact = repository.findByName(wanted);
        if (exact.isPresent()) {
            event.replyComponents(CombatAchievementView.card(exact.get())).useComponentsV2(true).queue();
            return;
        }

        List<Achievement> matches = repository.search(wanted, null, null, SUGGESTIONS_SHOWN + 1);
        if (matches.size() == 1) {
            event.replyComponents(CombatAchievementView.card(matches.getFirst())).useComponentsV2(true).queue();
        } else if (matches.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING, "I don't know a Combat Mastery achievement called **" + wanted + "**. Start typing a name and pick one of the suggestions.");
        } else {
            String list = String.join("\n", matches.stream().limit(SUGGESTIONS_SHOWN).map(a -> "• **" + a.name() + "** (" + a.tier() + ", " + a.subcategory() + ")").toList());
            Containers.replyEphemeral(event, Containers.INFO, "Several achievements match **" + wanted + "**. Try one of these:\n" + list + (matches.size() > SUGGESTIONS_SHOWN ? "\n…and more." : ""));
        }
    }

    /** Every name; BotCommands narrows them to the best matches for what has been typed so far. */
    @AutocompleteHandler(value = NAMES, mode = AutocompleteMode.FUZZY, showUserInput = false)
    public List<String> suggestNames(CommandAutoCompleteInteractionEvent event) {
        return repository.names();
    }
}
