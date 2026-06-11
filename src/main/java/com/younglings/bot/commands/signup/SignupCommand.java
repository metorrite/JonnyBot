package com.younglings.bot.commands.signup;

import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@Command
public class SignupCommand {
    private final SignupService signupService;

    public SignupCommand(SignupService signupService) {
        this.signupService = signupService;
    }

    @TopLevelSlashCommandData(description = "Create a signup form in this channel")
    @JDASlashCommand(name = "signup", subcommand = "queue", description = "Ordered queue — call next, skip, and notify users one at a time")
    public void onSignupQueue(
            GuildSlashEvent event,
            @SlashOption(description = "Embed title") String title,
            @SlashOption(description = "Channel where admin controls will be posted") TextChannel adminChannel,
            @SlashOption(description = "Message sent to the user when they reach the front of the queue") String notificationMessage,
            @SlashOption(description = "Maximum number of signups — leave blank for unlimited") @Nullable Integer maxSignups
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

    @JDASlashCommand(name = "signup", subcommand = "group", description = "Collective group — members join and are assigned a temporary role you can ping or pick a winner from")
    public void onSignupGroup(
            GuildSlashEvent event,
            @SlashOption(description = "Embed title") String title,
            @SlashOption(description = "Channel where admin controls will be posted") TextChannel adminChannel
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

    @JDASlashCommand(name = "signup", subcommand = "submission", description = "Submission form — users provide data entries (e.g. movie suggestions). Supports up to 3 fields.")
    public void onSignupSubmission(
            GuildSlashEvent event,
            @SlashOption(description = "Embed title") String title,
            @SlashOption(description = "Channel where admin controls will be posted") TextChannel adminChannel,
            @SlashOption(description = "First field label (e.g. Movie, Song, Idea)") String field1,
            @SlashOption(description = "First field type: TEXT, LINK, or IMAGE (default: TEXT)") @Nullable String field1Type,
            @SlashOption(description = "Is first field required? (default: yes)") @Nullable Boolean field1Required,
            @SlashOption(description = "Second field label (optional)") @Nullable String field2,
            @SlashOption(description = "Second field type: TEXT, LINK, or IMAGE (default: TEXT)") @Nullable String field2Type,
            @SlashOption(description = "Is second field required? (default: yes)") @Nullable Boolean field2Required,
            @SlashOption(description = "Third field label (optional)") @Nullable String field3,
            @SlashOption(description = "Third field type: TEXT, LINK, or IMAGE (default: TEXT)") @Nullable String field3Type,
            @SlashOption(description = "Is third field required? (default: yes)") @Nullable Boolean field3Required,
            @SlashOption(description = "Maximum number of submissions — leave blank for unlimited") @Nullable Integer maxEntries
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
