package com.younglings.bot.commands.signup;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.ModalTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;

@BService
public class SignupInteractionListener extends ListenerAdapter {
    private final SignupService signupService;

    public SignupInteractionListener(SignupService signupService) {
        this.signupService = signupService;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null) return;

        long guildId = event.getGuild().getIdLong();
        String id = event.getComponentId();

        switch (id) {
            case "signup_join" -> {
                TextInput usernameInput = TextInput.create(
                                "signup_username",
                                TextInputStyle.SHORT
                        )
                        .setPlaceholder("Enter your username")
                        .setRequired(true)
                        .setRequiredRange(1, 50)
                        .build();

                Modal modal = Modal.create("signup_submit", "Sign up")
                        .addComponents(Label.of("Username", usernameInput))
                        .build();

                event.replyModal(modal).queue();
            }

            case "signup_next" -> {
                SignupEntry newFirst = signupService.next(guildId);
                signupService.updateMessages(event.getGuild());
                event.reply("Moved to next signup.").setEphemeral(true).queue();
                notifyNewFirst(event, newFirst);
            }

            case "signup_skip" -> {
                SignupEntry newFirst = signupService.skip(guildId);
                signupService.updateMessages(event.getGuild());
                event.reply("Skipped current first signup.").setEphemeral(true).queue();
                notifyNewFirst(event, newFirst);
            }

            case "signup_remove" -> {
                SignupEntry newFirst = signupService.remove(guildId);
                signupService.updateMessages(event.getGuild());
                event.reply("Removed current first signup.").setEphemeral(true).queue();
                notifyNewFirst(event, newFirst);
            }

            case "signup_clear" -> {
                signupService.clear(guildId);
                signupService.updateMessages(event.getGuild());
                event.reply("Cleared all signups.").setEphemeral(true).queue();
            }

            case "signup_notify" -> {
                SignupEntry first = signupService.getFirst(guildId);

                if (first == null) {
                    event.reply("There is nobody to notify.")
                            .setEphemeral(true)
                            .queue();
                    return;
                }

                notifyNewFirst(event, first);

                event.reply("Notified the current first signup.")
                        .setEphemeral(true)
                        .queue();
            }
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getGuild() == null) return;

        if (!event.getModalId().equals("signup_submit")) {
            return;
        }

        String username = event.getValue("signup_username")
                .getAsString()
                .trim();

        boolean added = signupService.addUser(
                event.getGuild().getIdLong(),
                event.getUser().getIdLong(),
                username
        );

        if (!added) {
            event.reply("You are already signed up, that username is already listed, or the list is full.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        signupService.updateMessages(event.getGuild());

        event.reply("You have been added to the signup list.")
                .setEphemeral(true)
                .queue();
    }

    private void notifyNewFirst(ButtonInteractionEvent event, SignupEntry newFirst) {
        if (newFirst == null || event.getGuild() == null) return;

        SignupSession session = signupService.getSession(event.getGuild().getIdLong());
        if (session == null) return;

        var channel = event.getGuild().getTextChannelById(session.getPublicChannelId());
        if (channel == null) return;

        channel.sendMessage("<@" + newFirst.userId() + ">, " + session.getNotificationMessage()).queue();
    }
}
