package com.younglings.bot.commands.signup;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.modals.Modal;

import java.time.Duration;

@BService
public class SignupInteractionListener extends ListenerAdapter {
    private final SignupService signupService;

    public SignupInteractionListener(SignupService signupService) {
        this.signupService = signupService;
    }

    private boolean isAdmin(ButtonInteractionEvent event) {
        var member = event.getMember();
        return member != null && member.hasPermission(Permission.MANAGE_SERVER);
    }

    private boolean isAdmin(ModalInteractionEvent event) {
        var member = event.getMember();
        return member != null && member.hasPermission(Permission.MANAGE_SERVER);
    }

    private void replyNoPermission(ButtonInteractionEvent event) {
        event.reply("You don't have permission to use admin controls.")
                .setEphemeral(true)
                .queue();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null) return;

        String id = event.getComponentId();

        if (!id.startsWith("signup_") || !id.contains(":")) {
            return;
        }

        String action = id.split(":")[0];
        long signupId = signupService.parseSignupId(id);

        switch (action) {
            case "signup_join" -> {
                TextInput usernameInput = TextInput.create("signup_username", TextInputStyle.SHORT)
                        .setPlaceholder("Enter your username")
                        .setRequired(true)
                        .setRequiredRange(1, 50)
                        .build();

                Modal modal = Modal.create("signup_submit:" + signupId, "Sign up")
                        .addComponents(Label.of("Username", usernameInput))
                        .build();

                event.replyModal(modal).queue();
            }

            case "signup_leave" -> {
                event.reply("Are you sure you want to leave this queue? If you sign up again later, you will be added to the bottom and lose your current spot.")
                        .setEphemeral(true)
                        .addComponents(
                                ActionRow.of(
                                        Button.danger("signup_leave_confirm:" + signupId, "Yes, leave"),
                                        Button.secondary("signup_leave_cancel:" + signupId, "Cancel")
                                )
                        )
                        .queue();
            }

            case "signup_leave_cancel" -> {
                event.reply("Leave cancelled.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            case "signup_leave_confirm" -> {
                boolean removed = signupService.removeUser(signupId, event.getUser().getIdLong());

                if (!removed) {
                    event.reply("You are not currently signed up for this queue.")
                            .setEphemeral(true)
                            .delay(Duration.ofSeconds(5))
                            .flatMap(InteractionHook::deleteOriginal)
                            .queue();
                    return;
                }

                signupService.updateMessages(event.getGuild(), signupId);

                event.reply("You have been removed from the queue.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            case "signup_next" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupEntry newFirst = signupService.next(signupId);
                signupService.updateMessages(event.getGuild(), signupId);
                event.reply("Moved to next signup.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
                notifyNewFirst(event, signupId, newFirst);
            }

            case "signup_skip" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupEntry newFirst = signupService.skip(signupId);
                signupService.updateMessages(event.getGuild(), signupId);
                event.reply("Skipped current first signup.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
                notifyNewFirst(event, signupId, newFirst);
            }

            case "signup_remove" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupEntry newFirst = signupService.remove(signupId);
                signupService.updateMessages(event.getGuild(), signupId);
                event.reply("Removed current first signup.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
                notifyNewFirst(event, signupId, newFirst);
            }

            case "signup_clear" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                signupService.clear(signupId);
                signupService.updateMessages(event.getGuild(), signupId);
                event.reply("Cleared all signups.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            case "signup_notify" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupEntry first = signupService.getFirst(signupId);

                if (first == null) {
                    event.reply("There is nobody to notify.")
                            .setEphemeral(true)
                            .delay(Duration.ofSeconds(5))
                            .flatMap(InteractionHook::deleteOriginal)
                            .queue();
                    return;
                }

                notifyNewFirst(event, signupId, first);

                event.reply("Notified the current first signup.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            case "signup_admin_add" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                TextInput rsnInput = TextInput.create("signup_admin_rsn", TextInputStyle.SHORT)
                        .setPlaceholder("Enter RSN / username")
                        .setRequired(true)
                        .setRequiredRange(1, 50)
                        .build();

                TextInput discordInput = TextInput.create("signup_admin_discord", TextInputStyle.SHORT)
                        .setPlaceholder("Mention user or paste Discord ID")
                        .setRequired(true)
                        .setRequiredRange(1, 100)
                        .build();

                Modal modal = Modal.create("signup_admin_add_submit:" + signupId, "Add user to signup")
                        .addComponents(
                                Label.of("RSN / Username", rsnInput),
                                Label.of("Discord @ / ID", discordInput)
                        )
                        .build();

                event.replyModal(modal).queue();
            }

            case "signup_pause" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                String newStatus = signupService.togglePause(signupId);

                if (newStatus == null) {
                    event.reply("This signup no longer exists.")
                            .setEphemeral(true)
                            .queue();
                    return;
                }

                signupService.updateMessages(event.getGuild(), signupId);

                String message = "ACTIVE".equalsIgnoreCase(newStatus) ? "Signup resumed." : "Signup paused.";

                event.reply(message)
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            case "signup_delete" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                event.reply("Are you sure you want to delete this signup?")
                        .setEphemeral(true)
                        .addComponents(
                                ActionRow.of(
                                        Button.danger("signup_delete_confirm:" + signupId, "Yes, delete"),
                                        Button.secondary("signup_delete_cancel:" + signupId, "Cancel")
                                )
                        )
                        .queue();
            }

            case "signup_delete_cancel" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                event.reply("Delete cancelled.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            case "signup_delete_confirm" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                signupService.deleteSignup(event.getGuild(), signupId);

                event.reply("Signup deleted.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getGuild() == null) return;

        String modalId = event.getModalId();

        if (!modalId.startsWith("signup_") || !modalId.contains(":")) {
            return;
        }

        String action = modalId.split(":")[0];
        long signupId = signupService.parseSignupId(modalId);

        if (action.equals("signup_admin_add_submit")) {
            if (!isAdmin(event)) {
                event.reply("You don't have permission to use admin controls.")
                        .setEphemeral(true)
                        .queue();
                return;
            }

            String rsn = event.getValue("signup_admin_rsn").getAsString().trim();
            String discordRaw = event.getValue("signup_admin_discord").getAsString().trim();

            long userId;

            try {
                userId = parseUserId(discordRaw);
            } catch (NumberFormatException e) {
                event.reply("Invalid Discord user. Please mention the user or paste their Discord ID.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
                return;
            }

            boolean added = signupService.addManualUser(signupId, userId, rsn, event.getUser().getIdLong());

            if (!added) {
                event.reply("That user or RSN is already listed, the list is full, or no signup session exists.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
                return;
            }

            signupService.updateMessages(event.getGuild(), signupId);

            event.reply("Added `" + rsn + "` to the signup list for <@" + userId + ">.")
                    .setEphemeral(true)
                    .delay(Duration.ofSeconds(5))
                    .flatMap(InteractionHook::deleteOriginal)
                    .queue();

            return;
        }

        if (action.equals("signup_submit")) {
            String username = event.getValue("signup_username")
                    .getAsString()
                    .trim();

            boolean added = signupService.addUser(signupId, event.getUser().getIdLong(), username);

            if (!added) {
                event.reply("You are already signed up, that username is already listed, or the list is full.")
                        .setEphemeral(true)
                        .queue();
                return;
            }

            signupService.updateMessages(event.getGuild(), signupId);

            event.reply("You have been added to the signup list.")
                    .setEphemeral(true)
                    .queue();
        }
    }

    private void notifyNewFirst(ButtonInteractionEvent event, long signupId, SignupEntry newFirst) {
        if (newFirst == null || event.getGuild() == null) return;

        SignupSession session = signupService.getSessionById(signupId);
        if (session == null) return;

        SignupMessage publicMessage = signupService.getFirstActivePublicMessage(signupId);
        if (publicMessage == null) return;

        var channel = event.getGuild().getTextChannelById(publicMessage.channelId());
        if (channel == null) return;

        channel.sendMessage("<@" + newFirst.userId() + ">, " + session.notificationMessage()).queue();
    }

    private long parseUserId(String input) {
        String cleaned = input
                .replace("<@", "")
                .replace("!", "")
                .replace(">", "")
                .trim();

        return Long.parseLong(cleaned);
    }
}
