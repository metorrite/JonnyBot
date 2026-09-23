package com.younglings.bot.commands.signup;

import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Retired in favor of {@link SignupHubCommand}'s single {@code /signup} entry point with buttons
 * and modals — kept (not deleted) as reference/fallback, but no longer registered. BotCommands
 * validates that every {@code @JDASlashCommand} method's declaring class is {@code @Command} (and
 * throws at startup otherwise), so all the framework annotations are stripped here, not just the
 * class-level one — this is now plain, uncalled Java, not a disabled command.
 */
public class SignupCommand {
    private final SignupService signupService;

    public SignupCommand(SignupService signupService) {
        this.signupService = signupService;
    }

    public void onSignupQueue(
            GuildSlashEvent event,
            String title,
            TextChannel adminChannel,
            String notificationMessage,
            @Nullable Integer maxSignups
    ) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        signupService.createQueueSession(
                event.getGuild(),
                event.getChannel().asTextChannel(),
                adminChannel,
                title,
                notificationMessage,
                maxSignups,
                event.getUser().getIdLong()
        );

        event.reply("Queue signup created.").setEphemeral(true).queue();
    }

    public void onSignupGroup(
            GuildSlashEvent event,
            String title,
            TextChannel adminChannel
    ) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        signupService.createGroupSession(
                event.getGuild(),
                event.getChannel().asTextChannel(),
                adminChannel,
                title,
                event.getUser().getIdLong()
        );

        event.reply("Group signup created. A role is being set up — the panel will appear shortly.").setEphemeral(true).queue();
    }

    public void onSignupSubmission(
            GuildSlashEvent event,
            String title,
            TextChannel adminChannel,
            String field1,
            @Nullable String field1Type,
            @Nullable Boolean field1Required,
            @Nullable String field2,
            @Nullable String field2Type,
            @Nullable Boolean field2Required,
            @Nullable String field3,
            @Nullable String field3Type,
            @Nullable Boolean field3Required,
            @Nullable Integer maxEntries
    ) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        List<SubmissionField> fields = new ArrayList<>();
        fields.add(new SubmissionField(field1, SubmissionField.normalizeType(field1Type),
                field1Required == null || field1Required));
        if (field2 != null && !field2.isBlank())
            fields.add(new SubmissionField(field2, SubmissionField.normalizeType(field2Type),
                    field2Required == null || field2Required));
        if (field3 != null && !field3.isBlank())
            fields.add(new SubmissionField(field3, SubmissionField.normalizeType(field3Type),
                    field3Required == null || field3Required));

        signupService.createSubmissionSession(
                event.getGuild(),
                event.getChannel().asTextChannel(),
                adminChannel,
                title,
                fields,
                maxEntries,
                event.getUser().getIdLong()
        );

        event.reply("Submission signup created with " + fields.size() + " field(s).").setEphemeral(true).queue();
    }
}
