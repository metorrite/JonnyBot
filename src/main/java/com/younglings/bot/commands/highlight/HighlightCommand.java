package com.younglings.bot.commands.highlight;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.highlight.HighlightConfig;
import com.younglings.bot.highlight.HighlightMatcher;
import com.younglings.bot.highlight.HighlightRepository;
import com.younglings.bot.highlight.HighlightRepository.Keyword;
import com.younglings.bot.highlight.HighlightService;
import com.younglings.bot.i18n.Text;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.AutocompleteMode;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.annotations.AutocompleteHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * {@code /highlight add|remove|list|ignore}: the words a member wants a direct message about. The same words are on the member's website
 * profile. Nothing works until the server switches Highlights on (dashboard, Highlights page).
 */
@Command
public class HighlightCommand {
    static final String MY_WORDS = "highlight-my-words";

    private final HighlightService service;
    private final HighlightRepository repository;

    public HighlightCommand(HighlightService service, HighlightRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @TopLevelSlashCommandData(description = "Get a direct message when someone says a word you care about")
    @JDASlashCommand(name = "highlight", subcommand = "add", description = "Watch for a word")
    public void onAdd(GuildSlashEvent event,
                      @SlashOption(description = "The word or phrase to watch for") String word,
                      @SlashOption(description = "Only match it as a whole word (default yes); no also matches inside longer words") @Nullable Boolean wholeWord) {
        Guild guild = event.getGuild();
        String refusal = service.refusal(guild, event.getMember());
        if (refusal != null) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal);
            return;
        }
        HighlightConfig config = repository.config(guild.getIdLong());
        String cleaned = HighlightMatcher.clean(word);
        switch (service.add(guild, event.getUser().getIdLong(), word, wholeWord == null || wholeWord)) {
            case ADDED -> Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "highlight.you_ll_get_a_message_when", cleaned),
                    Text.of(event, "highlight.i_only_message_you_about_channels"));
            case DUPLICATE -> Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "highlight.you_re_already_watching", cleaned));
            case FULL -> Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "highlight.you_can_watch_at_most_words", config.maxKeywords()));
            case BAD_WORD -> Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "highlight.a_word_must_be_to_characters", HighlightConfig.MIN_WORD, HighlightConfig.MAX_WORD));
        }
    }

    @JDASlashCommand(name = "highlight", subcommand = "remove", description = "Stop watching for a word")
    public void onRemove(GuildSlashEvent event, @SlashOption(description = "The word to stop watching", autocomplete = MY_WORDS) String word) {
        boolean gone = repository.removeKeyword(event.getGuild().getIdLong(), event.getUser().getIdLong(), null, HighlightMatcher.clean(word));
        Containers.replyEphemeral(event, gone ? Containers.SUCCESS : Containers.INFO,
                gone ? Text.of(event, "highlight.no_longer_watching", HighlightMatcher.clean(word)) : Text.of(event, "highlight.you_weren_t_watching", HighlightMatcher.clean(word)));
    }

    @JDASlashCommand(name = "highlight", subcommand = "list", description = "See the words you watch and the channels you skip")
    public void onList(GuildSlashEvent event) {
        Guild guild = event.getGuild();
        long user = event.getUser().getIdLong();
        List<Keyword> words = repository.keywordsOf(guild.getIdLong(), user);
        Set<Long> ignored = repository.ignoredBy(guild.getIdLong(), user);
        HighlightConfig config = repository.config(guild.getIdLong());
        StringBuilder lines = new StringBuilder();
        for (Keyword k : words) lines.append("• **").append(k.word()).append("**").append(k.wholeWord() ? "" : " (also inside words)").append('\n');
        if (words.isEmpty()) lines.append("You aren't watching any words yet. Use `/highlight add`.\n");
        if (!ignored.isEmpty()) {
            lines.append("\nSkipped channels: ");
            lines.append(String.join(", ", ignored.stream().map(id -> "<#" + id + ">").toList())).append('\n');
        }
        if (!config.enabled()) lines.append("\n-# Highlights are switched off in this server, so nothing is sent for now.");
        Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "highlight.your_highlights_of", words.size(), config.maxKeywords()), lines.toString().stripTrailing());
    }

    @JDASlashCommand(name = "highlight", subcommand = "ignore", description = "Skip (or stop skipping) a channel for your highlights")
    public void onIgnore(GuildSlashEvent event, @SlashOption(description = "The channel to skip; run again to stop skipping it") GuildChannel channel) {
        Guild guild = event.getGuild();
        long user = event.getUser().getIdLong();
        Set<Long> ignored = repository.ignoredBy(guild.getIdLong(), user);
        boolean now = !ignored.contains(channel.getIdLong());
        if (now && ignored.size() >= HighlightConfig.MAX_MEMBER_IGNORES) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "highlight.you_can_skip_at_most_channels", HighlightConfig.MAX_MEMBER_IGNORES));
            return;
        }
        repository.setIgnored(guild.getIdLong(), user, channel.getIdLong(), now);
        Containers.replyEphemeral(event, Containers.SUCCESS, now ? Text.of(event, "highlight.skipping_for_your_highlights", channel.getAsMention()) : Text.of(event, "highlight.no_longer_skipping", channel.getAsMention()));
    }

    @AutocompleteHandler(value = MY_WORDS, mode = AutocompleteMode.FUZZY, showUserInput = false)
    public List<String> suggest(CommandAutoCompleteInteractionEvent event) {
        if (event.getGuild() == null) return List.of();
        return repository.keywordsOf(event.getGuild().getIdLong(), event.getUser().getIdLong()).stream().map(Keyword::word).limit(25).toList();
    }
}
