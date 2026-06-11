package com.younglings.bot.commands.poll;

import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.Permission;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
            @SlashOption(description = "First option (required)") String option1,
            @SlashOption(description = "Second option (required)") String option2,
            @SlashOption(description = "Third option") @Nullable String option3,
            @SlashOption(description = "Fourth option") @Nullable String option4,
            @SlashOption(description = "Fifth option") @Nullable String option5,
            @SlashOption(description = "Sixth option") @Nullable String option6,
            @SlashOption(description = "Allow each person to vote for multiple options? (default: no)") @Nullable Boolean multipleVotes,
            @SlashOption(description = "Hide who voted for what? (default: no)") @Nullable Boolean anonymous
    ) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        List<String> options = new ArrayList<>();
        options.add(option1.trim());
        options.add(option2.trim());
        if (option3 != null && !option3.isBlank()) options.add(option3.trim());
        if (option4 != null && !option4.isBlank()) options.add(option4.trim());
        if (option5 != null && !option5.isBlank()) options.add(option5.trim());
        if (option6 != null && !option6.isBlank()) options.add(option6.trim());

        pollService.createPoll(
                event.getGuild(),
                event.getChannel().asTextChannel(),
                title.trim(),
                anonymous != null && anonymous,
                multipleVotes != null && multipleVotes,
                options,
                event.getUser().getIdLong()
        );

        event.reply("Poll created!").setEphemeral(true).queue();
    }

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

        if (!event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("You need **Manage Server** permission to end polls.").setEphemeral(true).queue();
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
