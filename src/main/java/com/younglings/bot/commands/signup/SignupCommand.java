package com.younglings.bot.commands.signup;

import com.younglings.bot.commands.signup.SignupService;
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
        TextChannel signupChannel = event.getChannel().asTextChannel();

        event.deferReply(true).queue();

        signupService.createSession(
                event.getGuild(),
                signupChannel,
                adminChannel,
                title,
                notificationMessage,
                maxSignupNumber,
                hook -> event.getHook().sendMessage("Signup queue created.").setEphemeral(true).queue()
        );
    }
}
