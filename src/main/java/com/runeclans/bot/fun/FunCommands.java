package com.runeclans.bot.fun;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.annotations.DeclarationFilter;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * The fun commands: {@code /flip /roll /rps /8ball /choose /dadjoke /cat /dog}. Every one is guild-scoped and behind
 * {@link FunCommandFilter}, so a server only has them registered with Discord while it has switched them on (they are off by default,
 * see {@code HubCommand}); a switched-off command is not in that server's command list at all. The Hub filter still refuses a stale
 * click in the minutes before Discord's list catches up.
 */
@Command
public class FunCommands {
    private static final String NO_API = "I couldn't fetch one right now, try again in a moment.";

    private final FunApis apis;
    private final RandomGenerator random = RandomGenerator.getDefault();

    public FunCommands(FunApis apis) {
        this.apis = apis;
    }

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @DeclarationFilter(FunCommandFilter.class)
    @JDASlashCommand(name = "flip", description = "Flip a coin")
    public void onFlip(GuildSlashEvent event) {
        Containers.reply(event, Containers.SUCCESS, false, "🪙 **" + (FunLogic.flip(random) ? "Heads" : "Tails") + "**");
    }

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @DeclarationFilter(FunCommandFilter.class)
    @JDASlashCommand(name = "roll", description = "Roll dice, like 2d6 or d20+3")
    public void onRoll(GuildSlashEvent event,
                       @SlashOption(description = "Dice notation, e.g. 2d6, d20, 3d8+2 (default d20)") @Nullable String dice) {
        var roll = FunLogic.roll(dice == null || dice.isBlank() ? "d20" : dice, random);
        if (roll.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "fun.i_couldn_t_read_that_roll"),
                    Text.of(event, "fun.try_2d6_d20_or_3d8_2", FunLogic.MAX_DICE, FunLogic.MAX_SIDES));
            return;
        }
        var r = roll.get();
        String each = r.dice() == 1 && r.modifier() == 0 ? "" : "-# " + r.results().stream().map(String::valueOf).collect(Collectors.joining(" + "))
                + (r.modifier() == 0 ? "" : (r.modifier() > 0 ? " + " : " - ") + Math.abs(r.modifier()));
        if (each.isEmpty()) Containers.reply(event, Containers.SUCCESS, false, "🎲 **" + r.describe() + ":** " + r.total());
        else Containers.reply(event, Containers.SUCCESS, false, "🎲 **" + r.describe() + ":** " + r.total(), each);
    }

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @DeclarationFilter(FunCommandFilter.class)
    @JDASlashCommand(name = "rps", description = "Play rock paper scissors against the bot")
    public void onRps(GuildSlashEvent event,
                      @SlashOption(description = "Your move", usePredefinedChoices = true) FunLogic.Move move) {
        var mine = FunLogic.randomMove(random);
        var outcome = FunLogic.rps(move, mine);
        String result = switch (outcome) {
            case WIN -> "You win!";
            case LOSE -> "I win!";
            case DRAW -> "It's a draw.";
        };
        Containers.reply(event, outcome == FunLogic.Outcome.WIN ? Containers.SUCCESS : Containers.WARNING, false,
                Text.of(event, "fun.you_played_i_played", move.emoji, move.label, mine.emoji, mine.label), "### " + result);
    }

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @DeclarationFilter(FunCommandFilter.class)
    @JDASlashCommand(name = "8ball", description = "Ask the magic 8-ball a yes or no question")
    public void onEightBall(GuildSlashEvent event, @SlashOption(description = "Your question") String question) {
        Containers.reply(event, Containers.SUCCESS, false, "**" + question.strip() + "**", "🎱 " + FunLogic.eightBall(random));
    }

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @DeclarationFilter(FunCommandFilter.class)
    @JDASlashCommand(name = "choose", description = "Pick one from a list of options")
    public void onChoose(GuildSlashEvent event,
                         @SlashOption(description = "Options separated by commas or the word or, e.g. pizza, pasta or soup") String options) {
        List<String> list = FunLogic.options(options);
        if (list.size() < 2 || list.size() > FunLogic.MAX_CHOICES) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "fun.give_me_between_2_and_options", FunLogic.MAX_CHOICES),
                    Text.of(event, "fun.separate_them_with_commas_or_the"));
            return;
        }
        Containers.reply(event, Containers.SUCCESS, false, Text.of(event, "fun.i_choose", list.get(random.nextInt(list.size()))),
                Text.of(event, "fun.from", String.join(", ", list)));
    }

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @DeclarationFilter(FunCommandFilter.class)
    @JDASlashCommand(name = "dadjoke", description = "Tell a dad joke")
    public void onDadJoke(GuildSlashEvent event) {
        fromApi(event, FunApis.Kind.DAD_JOKE);
    }

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @DeclarationFilter(FunCommandFilter.class)
    @JDASlashCommand(name = "cat", description = "A random cat picture")
    public void onCat(GuildSlashEvent event) {
        fromApi(event, FunApis.Kind.CAT);
    }

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @DeclarationFilter(FunCommandFilter.class)
    @JDASlashCommand(name = "dog", description = "A random dog picture")
    public void onDog(GuildSlashEvent event) {
        fromApi(event, FunApis.Kind.DOG);
    }

    /** The answer comes from a web API, which can take longer than Discord's 3 seconds, so the reply is deferred first. */
    private void fromApi(GuildSlashEvent event, FunApis.Kind kind) {
        event.deferReply().queue(hook -> {
            String answer = apis.get(kind).orElse(null);
            if (answer == null) hook.editOriginal(NO_API).queue();
            else hook.editOriginal(kind == FunApis.Kind.DAD_JOKE ? "😄 " + answer : answer).queue();
        });
    }
}
