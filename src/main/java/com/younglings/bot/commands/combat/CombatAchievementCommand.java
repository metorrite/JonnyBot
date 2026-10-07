package com.younglings.bot.commands.combat;

import com.younglings.bot.combat.CombatAchievementModels.Achievement;
import com.younglings.bot.combat.CombatAchievementRepository;
import com.younglings.bot.commands.ticket.HelpRules;
import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.AutocompleteMode;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.annotations.AutocompleteHandler;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * {@code /ca boss: achievement: [tier:]}: look up a Combat Mastery achievement. Choosing the boss (and, if you like, the tier) first narrows the
 * achievement suggestions to that boss and tier, so the list never overflows Discord's 25 suggestions; the answer is the achievement's card (see
 * {@link CombatAchievementView}), posted in the channel so it can be shown to whoever is asking, such as in a ticket. Reads the local copy of the
 * wiki's data, so it never calls the wiki.
 */
@Command
public class CombatAchievementCommand {
    static final String BOSSES = "ca-bosses";
    static final String TIERS = "ca-tiers";
    static final String NAMES = "ca-achievement-names";
    private static final int SUGGESTIONS_SHOWN = 8;

    private final CombatAchievementRepository repository;

    public CombatAchievementCommand(CombatAchievementRepository repository) {
        this.repository = repository;
    }

    @JDASlashCommand(name = "ca", description = "Look up a Combat Mastery achievement: its tier, scores, and the wiki's tips")
    public void onCa(GuildSlashEvent event,
                     @SlashOption(description = "The boss or activity", autocomplete = BOSSES) String boss,
                     @SlashOption(description = "The achievement (suggestions follow your boss and tier)", autocomplete = NAMES) String achievement,
                     @SlashOption(description = "Only this tier (narrows the suggestions)", autocomplete = TIERS) @Nullable String tier) {
        String bossName = canonicalBoss(boss);
        if (bossName == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "I don't know a boss or activity called **" + boss.strip() + "**. Start typing it and pick one of the suggestions.");
            return;
        }
        Integer tierNumber = tierNumber(tier);
        if (tier != null && !tier.isBlank() && tierNumber == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "**" + tier.strip() + "** isn't a tier. Choose Easy, Medium, Hard, Elite, Master or Grandmaster.");
            return;
        }

        String wanted = achievement.strip();
        Optional<Achievement> exact = repository.findInBoss(bossName, wanted, tierNumber);
        if (exact.isPresent()) {
            event.replyComponents(CombatAchievementView.card(exact.get())).useComponentsV2(true).queue();
            return;
        }

        // Not an exact name: if it narrows to one achievement of this boss (and tier), that's the one.
        List<Achievement> matches = repository.search(wanted, tierNumber, bossName, SUGGESTIONS_SHOWN + 1);
        String where = bossName + (tierNumber == null ? "" : " (" + HelpRules.TIERS.get(tierNumber - 1) + ")");
        if (matches.size() == 1) {
            event.replyComponents(CombatAchievementView.card(matches.getFirst())).useComponentsV2(true).queue();
        } else if (matches.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING, "**" + where + "** has no achievement called **" + wanted + "**. Start typing it and pick one of the suggestions.");
        } else {
            String list = String.join("\n", matches.stream().limit(SUGGESTIONS_SHOWN).map(a -> "• **" + a.name() + "** (" + a.tier() + ")").toList());
            Containers.replyEphemeral(event, Containers.INFO, "Several **" + where + "** achievements match **" + wanted + "**. Try one of these:\n" + list
                    + (matches.size() > SUGGESTIONS_SHOWN ? "\n…and more." : ""));
        }
    }

    // ---------- suggestions ----------

    @AutocompleteHandler(value = BOSSES, mode = AutocompleteMode.FUZZY, showUserInput = false)
    public List<String> suggestBosses(CommandAutoCompleteInteractionEvent event) {
        return repository.bosses();
    }

    @AutocompleteHandler(value = TIERS, mode = AutocompleteMode.FUZZY, showUserInput = false)
    public List<String> suggestTiers(CommandAutoCompleteInteractionEvent event) {
        return HelpRules.TIERS;
    }

    /**
     * The achievements of the boss typed so far, and of the tier if one was chosen, which keeps the list short enough to show. BotCommands narrows it
     * to the best matches for what has been typed in this option. With no recognised boss yet it falls back to every name, so the command still works
     * if someone fills the options in a different order.
     */
    @AutocompleteHandler(value = NAMES, mode = AutocompleteMode.FUZZY, showUserInput = false)
    public List<String> suggestNames(CommandAutoCompleteInteractionEvent event) {
        String boss = canonicalBoss(text(event.getOption("boss")));
        if (boss == null) return repository.names();
        return repository.names(boss, tierNumber(text(event.getOption("tier"))));
    }

    private static String text(OptionMapping option) {
        return option == null ? null : option.getAsString();
    }

    /** The boss as the catalogue spells it, matched ignoring case, or {@code null} if there's no such boss. */
    String canonicalBoss(String typed) {
        if (typed == null || typed.isBlank()) return null;
        return repository.bosses().stream().filter(b -> b.equalsIgnoreCase(typed.strip())).findFirst().orElse(null);
    }

    /** 1 to 6 for a tier name (any case), or {@code null} for none or an unknown one. */
    static Integer tierNumber(String tier) {
        if (tier == null || tier.isBlank()) return null;
        int index = HelpRules.TIERS.indexOf(HelpRules.pingGroup(tier));
        return index < 0 ? null : index + 1;
    }
}
