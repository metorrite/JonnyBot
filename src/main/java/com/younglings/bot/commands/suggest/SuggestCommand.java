package com.younglings.bot.commands.suggest;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.suggestion.Suggestion;
import com.younglings.bot.suggestion.SuggestionService;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;

/** {@code /suggest idea}: posts the idea in the server's suggestions channel for everyone to vote on. */
@Command
public class SuggestCommand {
    private final SuggestionService service;

    public SuggestCommand(SuggestionService service) {
        this.service = service;
    }

    @JDASlashCommand(name = "suggest", description = "Post an idea in the suggestions channel for everyone to vote on")
    public void onSuggest(GuildSlashEvent event, @SlashOption(description = "Your idea") String idea) {
        String text = idea.strip();
        if (text.isEmpty() || text.length() > Suggestion.MAX_CONTENT) {
            Containers.replyEphemeral(event, Containers.WARNING, "Write your idea in at most " + Suggestion.MAX_CONTENT + " characters.");
            return;
        }
        try {
            Suggestion s = service.submit(event.getGuild(), event.getUser().getIdLong(), text);
            Containers.replyEphemeral(event, Containers.SUCCESS, "Thanks! Your suggestion is up as #" + s.id() + ".");
        } catch (SuggestionService.SuggestionException e) {
            Containers.replyEphemeral(event, Containers.WARNING, e.getMessage());
        }
    }
}
