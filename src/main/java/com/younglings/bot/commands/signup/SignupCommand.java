package com.younglings.bot.commands.signup;

import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

@Command
public class SignupCommand {
    private final SignupService signupService;

    public SignupCommand(SignupService signupService) {
        this.signupService = signupService;
    }

    @JDASlashCommand(name = "signup", description = "Creates a signup queue")
    public void onSignup(
            GuildSlashEvent event,
            @SlashOption(description = "Embed title") String title,
            @SlashOption(description = "Admin control channel") TextChannel adminChannel,
            @SlashOption(description = "Message sent when someone becomes first") String notificationMessage,
            @SlashOption(description = "Max signup number, leave blank for no limit") Integer maxSignupNumber
    ) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        TextChannel signupChannel = event.getChannel().asTextChannel();

        signupService.createSession(
                event.getGuild(),
                signupChannel,
                adminChannel,
                title,
                notificationMessage,
                maxSignupNumber,
                event.getUser().getIdLong()
        );

        event.reply("Signup queue created.")
                .setEphemeral(true)
                .queue();
    }
}
