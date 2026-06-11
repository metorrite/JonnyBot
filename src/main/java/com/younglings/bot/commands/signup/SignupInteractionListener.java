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
import java.util.ArrayList;
import java.util.List;

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

        if (!id.startsWith("signup_") || !id.contains(":")) return;

        String action = id.split(":")[0];
        long signupId = signupService.parseSignupId(id);

        switch (action) {

            // --- Public: join ---
            case "signup_join" -> {
                SignupSession session = signupService.getSessionById(signupId);
                if (session == null) {
                    event.reply("This signup no longer exists.").setEphemeral(true).queue();
                    return;
                }

                if (!signupService.isSignupActive(signupId)) {
                    event.reply("This signup is currently paused.").setEphemeral(true).queue();
                    return;
                }

                switch (session.type()) {
                    case QUEUE -> {
                        TextInput usernameInput = TextInput.create("signup_username", TextInputStyle.SHORT)
                                .setPlaceholder("Enter your username / RSN")
                                .setRequired(true)
                                .setRequiredRange(1, 50)
                                .build();

                        Modal modal = Modal.create("signup_submit:" + signupId, "Sign up")
                                .addComponents(Label.of("Username", usernameInput))
                                .build();

                        event.replyModal(modal).queue();
                    }

                    case GROUP -> {
                        boolean added = signupService.addUser(
                                event.getGuild(), signupId,
                                event.getUser().getIdLong(),
                                String.valueOf(event.getUser().getIdLong()),
                                null
                        );

                        if (!added) {
                            event.reply("You are already in this group, or it is currently paused.")
                                    .setEphemeral(true).queue();
                            return;
                        }

                        signupService.updateMessages(event.getGuild(), signupId);
                        event.reply("You have joined the group!").setEphemeral(true).queue();
                    }

                    case SUBMISSION -> {
                        List<SubmissionField> fields = SubmissionField.deserialize(session.submissionFields());
                        Modal modal = buildSubmissionModal(signupId, fields);
                        event.replyModal(modal).queue();
                    }
                }
            }

            // --- Public: leave ---
            case "signup_leave" -> {
                SignupSession session = signupService.getSessionById(signupId);
                String leaveWarning = session != null && session.type() == SignupType.QUEUE
                        ? "Are you sure you want to leave this queue? If you sign up again later, you will be added to the bottom and lose your current spot."
                        : "Are you sure you want to leave?";

                event.reply(leaveWarning)
                        .setEphemeral(true)
                        .addComponents(ActionRow.of(
                                Button.danger("signup_leave_confirm:" + signupId, "Yes, leave"),
                                Button.secondary("signup_leave_cancel:" + signupId, "Cancel")
                        ))
                        .queue();
            }

            case "signup_leave_cancel" -> {
                event.editMessage("Leave cancelled.")
                        .setComponents()
                        .delay(Duration.ofSeconds(3))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            case "signup_leave_confirm" -> {
                boolean removed = signupService.removeUser(
                        event.getGuild(), signupId, event.getUser().getIdLong());

                if (!removed) {
                    event.editMessage("You are not currently signed up.")
                            .setComponents()
                            .delay(Duration.ofSeconds(3))
                            .flatMap(InteractionHook::deleteOriginal)
                            .queue();
                    return;
                }

                signupService.updateMessages(event.getGuild(), signupId);
                event.editMessage("You have been removed.")
                        .setComponents()
                        .delay(Duration.ofSeconds(3))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            // --- Admin: queue-only ---
            case "signup_next" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupEntry newFirst = signupService.next(signupId);
                signupService.updateMessages(event.getGuild(), signupId);
                notifyNewFirst(event, signupId, newFirst);
                event.reply("Moved to next signup.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            case "signup_skip" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupEntry newFirst = signupService.skip(signupId);
                signupService.updateMessages(event.getGuild(), signupId);
                notifyNewFirst(event, signupId, newFirst);
                event.reply("Skipped current first signup.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            case "signup_remove" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupEntry newFirst = signupService.remove(signupId);
                signupService.updateMessages(event.getGuild(), signupId);
                notifyNewFirst(event, signupId, newFirst);
                event.reply("Removed current first signup.")
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

            // --- Admin: group-only ---
            case "signup_notify_all" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupSession session = signupService.getSessionById(signupId);
                if (session == null || session.groupRoleId() == null) {
                    event.reply("Group role not found.").setEphemeral(true).queue();
                    return;
                }

                SignupMessage publicMessage = signupService.getFirstActivePublicMessage(signupId);
                if (publicMessage == null) {
                    event.reply("No public panel found to send the ping in.").setEphemeral(true).queue();
                    return;
                }

                var channel = event.getGuild().getTextChannelById(publicMessage.channelId());
                if (channel == null) {
                    event.reply("Signup channel not found.").setEphemeral(true).queue();
                    return;
                }

                channel.sendMessage("<@&" + session.groupRoleId() + ">").queue();

                event.reply("Group notified!")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            // --- Admin: pick random (submission) / pick winner (group) ---
            case "signup_pick_random", "signup_pick_winner" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                List<SignupEntry> entries = signupService.getEntries(signupId);
                if (entries.isEmpty()) {
                    event.reply("There are no entries to pick from.")
                            .setEphemeral(true)
                            .delay(Duration.ofSeconds(5))
                            .flatMap(InteractionHook::deleteOriginal)
                            .queue();
                    return;
                }

                String label = action.equals("signup_pick_random") ? "random submission" : "winner";

                event.reply("Pick a " + label + " from **" + entries.size() + "** entr"
                                + (entries.size() == 1 ? "y" : "ies") + "? **This will close the signup.**")
                        .setEphemeral(true)
                        .addComponents(ActionRow.of(
                                Button.success("signup_pick_confirm:" + signupId, "Yes, pick!"),
                                Button.secondary("signup_pick_cancel:" + signupId, "Cancel")
                        ))
                        .queue();
            }

            case "signup_pick_confirm" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                // Capture session and winner before deleteSignup clears entries
                SignupSession session = signupService.getSessionById(signupId);
                SignupEntry winner = signupService.pickRandom(signupId);

                if (winner == null || session == null) {
                    event.editMessage("No entries found — nothing was picked.")
                            .setComponents()
                            .delay(Duration.ofSeconds(5))
                            .flatMap(InteractionHook::deleteOriginal)
                            .queue();
                    return;
                }

                // Post winner announcement in the public channel
                SignupMessage publicMessage = signupService.getFirstActivePublicMessage(signupId);
                if (publicMessage != null) {
                    var channel = event.getGuild().getTextChannelById(publicMessage.channelId());
                    if (channel != null) {
                        channel.sendMessageEmbeds(signupService.buildWinnerEmbed(session, winner).build()).queue();
                    }
                }

                // Close the signup
                signupService.deleteSignup(event.getGuild(), signupId);

                event.editMessage("Winner picked! The signup is now closed.")
                        .setComponents()
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            case "signup_pick_cancel" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                event.editMessage("Pick cancelled.")
                        .setComponents()
                        .delay(Duration.ofSeconds(3))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            // --- Admin: remove member/entry by Discord ID ---
            case "signup_admin_remove" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupSession session = signupService.getSessionById(signupId);
                String label = session != null && session.type() == SignupType.GROUP ? "member" : "entry";

                TextInput discordInput = TextInput.create("signup_admin_remove_discord", TextInputStyle.SHORT)
                        .setPlaceholder("Mention user or paste Discord ID")
                        .setRequired(true)
                        .setRequiredRange(1, 100)
                        .build();

                Modal modal = Modal.create("signup_admin_remove_submit:" + signupId, "Remove " + label)
                        .addComponents(Label.of("Discord @ / ID", discordInput))
                        .build();

                event.replyModal(modal).queue();
            }

            // --- Admin: add (modal content differs by type) ---
            case "signup_admin_add" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                SignupSession session = signupService.getSessionById(signupId);
                if (session == null) {
                    event.reply("This signup no longer exists.").setEphemeral(true).queue();
                    return;
                }

                event.replyModal(buildAdminAddModal(session)).queue();
            }

            // --- Admin: shared ---
            case "signup_clear" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                signupService.clear(event.getGuild(), signupId);
                signupService.updateMessages(event.getGuild(), signupId);
                event.reply("Cleared all entries.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            case "signup_pause" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                String newStatus = signupService.togglePause(signupId);

                if (newStatus == null) {
                    event.reply("This signup no longer exists.").setEphemeral(true).queue();
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
                        .addComponents(ActionRow.of(
                                Button.danger("signup_delete_confirm:" + signupId, "Yes, delete"),
                                Button.secondary("signup_delete_cancel:" + signupId, "Cancel")
                        ))
                        .queue();
            }

            case "signup_delete_cancel" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                event.editMessage("Delete cancelled.")
                        .setComponents()
                        .delay(Duration.ofSeconds(3))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            case "signup_delete_confirm" -> {
                if (!isAdmin(event)) { replyNoPermission(event); return; }

                signupService.deleteSignup(event.getGuild(), signupId);

                event.editMessage("Signup deleted.")
                        .setComponents()
                        .delay(Duration.ofSeconds(3))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getGuild() == null) return;

        String modalId = event.getModalId();

        if (!modalId.startsWith("signup_") || !modalId.contains(":")) return;

        String action = modalId.split(":")[0];
        long signupId = signupService.parseSignupId(modalId);

        switch (action) {

            case "signup_submit" -> {
                SignupSession session = signupService.getSessionById(signupId);
                if (session == null) {
                    event.reply("This signup no longer exists.").setEphemeral(true).queue();
                    return;
                }

                long userId = event.getUser().getIdLong();
                boolean added = false;

                switch (session.type()) {
                    case QUEUE -> {
                        String username = event.getValue("signup_username").getAsString().trim();
                        added = signupService.addUser(event.getGuild(), signupId, userId, username, null);
                    }
                    case SUBMISSION -> {
                        List<SubmissionField> fields = SubmissionField.deserialize(session.submissionFields());
                        List<String> values = new ArrayList<>();
                        for (int i = 0; i < fields.size(); i++) {
                            var valueHolder = event.getValue("signup_field_" + i);
                            values.add(valueHolder != null ? valueHolder.getAsString().trim() : "");
                        }
                        String submissionValue = SubmissionField.serializeValues(values);
                        added = signupService.addUser(event.getGuild(), signupId, userId,
                                String.valueOf(userId), submissionValue);
                    }
                    case GROUP -> {
                        event.reply("Unexpected modal for this signup type.").setEphemeral(true).queue();
                        return;
                    }
                }

                if (!added) {
                    event.reply("You are already signed up, the entry is a duplicate, or the list is full.")
                            .setEphemeral(true).queue();
                    return;
                }

                signupService.updateMessages(event.getGuild(), signupId);
                event.reply("You have been added!").setEphemeral(true).queue();
            }

            case "signup_admin_add_submit" -> {
                if (!isAdmin(event)) {
                    event.reply("You don't have permission to use admin controls.").setEphemeral(true).queue();
                    return;
                }

                SignupSession session = signupService.getSessionById(signupId);
                if (session == null) {
                    event.reply("This signup no longer exists.").setEphemeral(true).queue();
                    return;
                }

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

                boolean added = false;

                switch (session.type()) {
                    case QUEUE -> {
                        String rsn = event.getValue("signup_admin_rsn").getAsString().trim();
                        added = signupService.addManualUser(event.getGuild(), signupId, userId, rsn,
                                null, event.getUser().getIdLong());
                    }
                    case GROUP -> {
                        added = signupService.addManualUser(event.getGuild(), signupId, userId,
                                String.valueOf(userId), null, event.getUser().getIdLong());
                    }
                    case SUBMISSION -> {
                        List<SubmissionField> fields = SubmissionField.deserialize(session.submissionFields());
                        List<String> values = new ArrayList<>();
                        for (int i = 0; i < fields.size(); i++) {
                            var valueHolder = event.getValue("signup_field_" + i);
                            values.add(valueHolder != null ? valueHolder.getAsString().trim() : "");
                        }
                        String submissionValue = SubmissionField.serializeValues(values);
                        added = signupService.addManualUser(event.getGuild(), signupId, userId,
                                String.valueOf(userId), submissionValue, event.getUser().getIdLong());
                    }
                }

                if (!added) {
                    event.reply("That user or entry is already listed, the list is full, or no signup session exists.")
                            .setEphemeral(true)
                            .delay(Duration.ofSeconds(5))
                            .flatMap(InteractionHook::deleteOriginal)
                            .queue();
                    return;
                }

                signupService.updateMessages(event.getGuild(), signupId);
                event.reply("Added <@" + userId + "> to the signup.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            case "signup_admin_remove_submit" -> {
                if (!isAdmin(event)) {
                    event.reply("You don't have permission to use admin controls.").setEphemeral(true).queue();
                    return;
                }

                String discordRaw = event.getValue("signup_admin_remove_discord").getAsString().trim();
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

                boolean removed = signupService.removeUser(event.getGuild(), signupId, userId);

                if (!removed) {
                    event.reply("That user is not in this signup.")
                            .setEphemeral(true)
                            .delay(Duration.ofSeconds(5))
                            .flatMap(InteractionHook::deleteOriginal)
                            .queue();
                    return;
                }

                signupService.updateMessages(event.getGuild(), signupId);
                event.reply("Removed <@" + userId + "> from the signup.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }
        }
    }

    // --- Modal builders ---

    private Modal buildSubmissionModal(long signupId, List<SubmissionField> fields) {
        Modal.Builder mb = Modal.create("signup_submit:" + signupId, "Submit");

        for (int i = 0; i < fields.size(); i++) {
            SubmissionField field = fields.get(i);
            String placeholder = switch (field.type()) {
                case SubmissionField.TYPE_IMAGE -> "Paste an image URL";
                case SubmissionField.TYPE_LINK  -> "Paste a link URL";
                default                         -> "Enter " + field.label().toLowerCase();
            };

            TextInput.Builder inputBuilder = TextInput.create("signup_field_" + i, TextInputStyle.SHORT)
                    .setPlaceholder(placeholder)
                    .setRequired(field.required());
            if (field.required()) inputBuilder.setRequiredRange(1, 200);
            else inputBuilder.setRequiredRange(0, 200);

            String labelText = field.required() ? field.label() : field.label() + " (optional)";
            mb.addComponents(Label.of(labelText, inputBuilder.build()));
        }

        return mb.build();
    }

    private Modal buildAdminAddModal(SignupSession session) {
        long signupId = session.signupId();

        TextInput discordInput = TextInput.create("signup_admin_discord", TextInputStyle.SHORT)
                .setPlaceholder("Mention user or paste Discord ID")
                .setRequired(true)
                .setRequiredRange(1, 100)
                .build();

        return switch (session.type()) {
            case QUEUE -> {
                TextInput rsnInput = TextInput.create("signup_admin_rsn", TextInputStyle.SHORT)
                        .setPlaceholder("Enter RSN / username")
                        .setRequired(true)
                        .setRequiredRange(1, 50)
                        .build();

                yield Modal.create("signup_admin_add_submit:" + signupId, "Add user to signup")
                        .addComponents(
                                Label.of("RSN / Username", rsnInput),
                                Label.of("Discord @ / ID", discordInput)
                        )
                        .build();
            }

            case GROUP -> Modal.create("signup_admin_add_submit:" + signupId, "Add member to group")
                    .addComponents(Label.of("Discord @ / ID", discordInput))
                    .build();

            case SUBMISSION -> {
                List<SubmissionField> fields = SubmissionField.deserialize(session.submissionFields());
                Modal.Builder mb = Modal.create("signup_admin_add_submit:" + signupId, "Add entry");
                mb.addComponents(Label.of("Discord @ / ID", discordInput));

                for (int i = 0; i < fields.size(); i++) {
                    SubmissionField field = fields.get(i);
                    String placeholder = switch (field.type()) {
                        case SubmissionField.TYPE_IMAGE -> "Paste an image URL";
                        case SubmissionField.TYPE_LINK  -> "Paste a link URL";
                        default                         -> "Enter " + field.label().toLowerCase();
                    };

                    TextInput.Builder inputBuilder = TextInput.create("signup_field_" + i, TextInputStyle.SHORT)
                            .setPlaceholder(placeholder)
                            .setRequired(field.required());
                    if (field.required()) inputBuilder.setRequiredRange(1, 200);
                    else inputBuilder.setRequiredRange(0, 200);

                    String labelText = field.required() ? field.label() : field.label() + " (optional)";
                    mb.addComponents(Label.of(labelText, inputBuilder.build()));
                }

                yield mb.build();
            }
        };
    }

    // --- Helpers ---

    private void notifyNewFirst(ButtonInteractionEvent event, long signupId, SignupEntry newFirst) {
        if (newFirst == null || event.getGuild() == null) return;

        SignupSession session = signupService.getSessionById(signupId);
        if (session == null || session.notificationMessage() == null) return;

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
