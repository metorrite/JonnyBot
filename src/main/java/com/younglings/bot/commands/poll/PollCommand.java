package com.younglings.bot.commands.poll;

import com.younglings.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.annotations.Filter;
import io.github.freya022.botcommands.api.commands.annotations.VarArgs;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Command
public class PollCommand {
    private static final Logger log = LoggerFactory.getLogger(PollCommand.class);

    private final PollService pollService;

    public PollCommand(PollService pollService) {
        this.pollService = pollService;
    }

    @TopLevelSlashCommandData(description = "Poll management")
    @JDASlashCommand(name = "poll", subcommand = "create", description = "Create a poll in this channel")
    public void onPollCreate(
            GuildSlashEvent event,
            @SlashOption(description = "Poll question / title") String title,
            @SlashOption(name = "option", description = "A poll option, in order (2 required, up to 6 total)")
            @VarArgs(value = 6, numRequired = 2) List<String> options,
            @SlashOption(description = "Allow each person to vote for multiple options? (default: no)") @Nullable Boolean multipleVotes,
            @SlashOption(description = "Hide who voted for what? (default: no)") @Nullable Boolean anonymous
    ) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        pollService.createPoll(
                event.getGuild(),
                event.getChannel().asTextChannel(),
                title.trim(),
                anonymous != null && anonymous,
                multipleVotes != null && multipleVotes,
                options.stream().map(String::trim).toList(),
                event.getUser().getIdLong()
        );

        event.reply("Poll created!").setEphemeral(true).queue();
    }

    @Filter(AdminRoleFilter.class)
    @JDASlashCommand(name = "poll", subcommand = "end", description = "Close an active poll")
    public void onPollEnd(
            GuildSlashEvent event,
            @SlashOption(description = "Title or part of the poll title") String title,
            @SlashOption(description = "DM you a full breakdown of who voted for what? (default: no)") @Nullable Boolean dmResults
    ) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        PollSession poll = pollService.findByTitle(event.getGuild().getIdLong(), title);
        if (poll == null) {
            event.reply("No active poll found matching **\"" + title + "\"**.")
                    .setEphemeral(true).queue();
            return;
        }

        if (dmResults != null && dmResults) {
            String summary = pollService.buildResultsSummary(poll.pollId());
            event.getUser().openPrivateChannel().queue(
                    dm -> dm.sendMessage(summary).queue(
                            null,
                            err -> log.warn("Failed to DM poll results to {}", event.getUser().getIdLong(), err)),
                    err -> log.warn("Could not open DM channel to {}", event.getUser().getIdLong(), err));
        }

        pollService.closePoll(event.getGuild(), poll.pollId());

        event.reply("Poll **\"" + poll.title() + "\"** has been closed.").setEphemeral(true).queue();
    }
}
