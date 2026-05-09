package com.younglings.bot.commands.signup;

import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.InteractionHook;

import java.awt.Color;
import java.time.Duration;
import java.util.List;

@Command
public class SignupManagementCommand {
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

        StringBuilder description = new StringBuilder();

        for (SignupSession signup : signups) {
            String status = signupService.getSignupStatus(signup.getSignupId());

            description.append("**")
                    .append(signup.getSignupId())
                    .append("** — ")
                    .append(signup.getTitle())
                    .append("\nStatus: `")
                    .append(status)
                    .append("`\n\n");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Current Signups")
                .setDescription(description.toString());

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
}
