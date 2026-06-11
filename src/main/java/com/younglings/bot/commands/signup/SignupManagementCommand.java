package com.younglings.bot.commands.signup;

import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.InteractionHook;

import java.awt.Color;
import java.time.Duration;
import java.util.List;

@Command
public class SignupManagementCommand {
    private static final int MAX_LIST_ENTRIES = 20;
    private static final int EMBED_DESCRIPTION_LIMIT = 4000;

    private final SignupService signupService;

    public SignupManagementCommand(SignupService signupService) {
        this.signupService = signupService;
    }

    @JDASlashCommand(name = "signuplist", description = "Lists all current signup queues")
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
                description.append("*...and more. Use `/signuplist` filters to narrow results.*\n");
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

    @JDASlashCommand(name = "signuppost", description = "Posts another signup panel in this channel, use /signuplist for a list of active signup forms")
    public void onSignupPost(
            GuildSlashEvent event,
            @SlashOption(description = "Which panel to post: PUBLIC or ADMIN") String panelType,
            @SlashOption(description = "Signup ID from /signuplist") Long signupId
    ) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        TextChannel channel = event.getChannel().asTextChannel();

        SignupPanelType type;

        try {
            type = SignupPanelType.valueOf(panelType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            event.reply("Panel type must be `PUBLIC` or `ADMIN`.")
                    .setEphemeral(true)
                    .delay(Duration.ofSeconds(5))
                    .flatMap(InteractionHook::deleteOriginal)
                    .queue();
            return;
        }

        try {
            signupService.postSignupEmbed(event.getGuild(), channel, signupId, type);

            event.reply("Posted `" + type + "` signup panel.")
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

    @JDASlashCommand(name = "updatepanels", description = "Refreshes all active signup panels with the latest layout and buttons")
    public void onUpdatePanels(GuildSlashEvent event) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        var member = event.getMember();
        if (member == null || !member.hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("You don't have permission to use this command.")
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
