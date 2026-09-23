package com.younglings.bot.commands.signup;

import net.dv8tion.jda.api.EmbedBuilder;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.InteractionHook;

import java.awt.Color;
import java.time.Duration;
import java.util.List;

/**
 * Retired in favor of {@link SignupHubCommand}'s single {@code /signup} entry point with buttons
 * and modals (its list/post/refresh logic lives in {@link SignupInteractionListener} now) — kept
 * (not deleted) as reference/fallback, but no longer registered. BotCommands validates that every
 * {@code @JDASlashCommand} method's declaring class is {@code @Command} (and throws at startup
 * otherwise), so all the framework annotations are stripped here, not just the class-level one —
 * this is now plain, uncalled Java, not a disabled command.
 */
public class SignupManagementCommand {
    private static final int MAX_LIST_ENTRIES = 20;
    private static final int EMBED_DESCRIPTION_LIMIT = 4000;

    private final SignupService signupService;

    public SignupManagementCommand(SignupService signupService) {
        this.signupService = signupService;
    }

    public void onSignupList(GuildSlashEvent event) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        List<SignupSession> signups = signupService.getVisibleSignups(event.getGuild().getIdLong());

        if (signups.isEmpty()) {
            event.reply("There are no current signups.")
                    .setEphemeral(true)
                    .delay(Duration.ofSeconds(5))
                    .flatMap(InteractionHook::deleteOriginal)
                    .queue();
            return;
        }

        List<SignupSession> page = signups.size() > MAX_LIST_ENTRIES
                ? signups.subList(0, MAX_LIST_ENTRIES)
                : signups;

        StringBuilder description = new StringBuilder();

        for (SignupSession signup : page) {
            String status = signupService.getSignupStatus(signup.signupId());

            String entry = "**" + signup.signupId() + "** — " + signup.title()
                    + "\nType: `" + signup.type().name() + "` • Status: `" + status + "`\n\n";

            if (description.length() + entry.length() > EMBED_DESCRIPTION_LIMIT) {
                description.append("*...and more. Use `/signup list` filters to narrow results.*\n");
                break;
            }

            description.append(entry);
        }

        if (signups.size() > MAX_LIST_ENTRIES) {
            description.append("*Showing ")
                    .append(MAX_LIST_ENTRIES)
                    .append(" of ")
                    .append(signups.size())
                    .append(" signups.*");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Current Signups")
                .setDescription(description.toString())
                .setColor(Color.BLUE);

        event.replyEmbeds(embed.build())
                .setEphemeral(true)
                .queue();
    }

    public void onSignupPost(
            GuildSlashEvent event,
            SignupPanelType panelType,
            Long signupId
    ) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        TextChannel channel = event.getChannel().asTextChannel();

        try {
            signupService.postSignupEmbed(event.getGuild(), channel, signupId, panelType);

            event.reply("Posted `" + panelType + "` signup panel.")
                    .setEphemeral(true)
                    .delay(Duration.ofSeconds(5))
                    .flatMap(InteractionHook::deleteOriginal)
                    .queue();

        } catch (IllegalArgumentException e) {
            event.reply("Could not find that signup ID.")
                    .setEphemeral(true)
                    .delay(Duration.ofSeconds(5))
                    .flatMap(InteractionHook::deleteOriginal)
                    .queue();
        }
    }

    public void onUpdatePanels(GuildSlashEvent event) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        List<SignupSession> signups = signupService.getVisibleSignups(event.getGuild().getIdLong());

        if (signups.isEmpty()) {
            event.reply("No active signup panels to update.")
                    .setEphemeral(true)
                    .delay(Duration.ofSeconds(5))
                    .flatMap(InteractionHook::deleteOriginal)
                    .queue();
            return;
        }

        var guild = event.getGuild();
        int count = signups.size();

        for (SignupSession signup : signups) {
            signupService.updateMessages(guild, signup.signupId());
        }

        event.reply("Refreshing " + count + " signup panel" + (count == 1 ? "" : "s") + ". Changes will appear shortly.")
                .setEphemeral(true)
                .delay(Duration.ofSeconds(5))
                .flatMap(InteractionHook::deleteOriginal)
                .queue();
    }
}
