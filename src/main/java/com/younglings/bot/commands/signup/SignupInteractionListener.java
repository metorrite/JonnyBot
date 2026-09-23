package com.younglings.bot.commands.signup;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.SelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@BService
public class SignupInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(SignupInteractionListener.class);
    private static final int MAX_LIST_ENTRIES = 20;
    private static final int EMBED_DESCRIPTION_LIMIT = 4000;

    private final SignupService signupService;
    private final BotConfig botConfig;
    private final AdminRoleFilter adminRoleFilter;

    public SignupInteractionListener(SignupService signupService, BotConfig botConfig, AdminRoleFilter adminRoleFilter) {
        this.signupService = signupService;
        this.botConfig = botConfig;
        this.adminRoleFilter = adminRoleFilter;
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

        try {
            handleButton(event, id);
        } catch (Exception e) {
            log.error("Unhandled exception in signup button interaction '{}'", id, e);
            try {
                if (!event.isAcknowledged()) {
                    event.reply("An unexpected error occurred. Please try again or contact an admin.")
                            .setEphemeral(true).queue();
                }
            } catch (Exception ignored) {}
        }
    }

    private void handleButton(ButtonInteractionEvent event, String id) {
        if (id.startsWith("signup_builder_")) {
            handleBuilderButton(event, id);
            return;
        }
        if (id.startsWith("signup_dev_close_all")) {
            handleDevCloseAll(event, id);
            return;
        }
        if (id.startsWith("signup_hub_")) {
            handleHubButton(event, id);
            return;
        }

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

                signupService.deletePingMessages(event.getGuild(), signupId);

                long guildId = event.getGuild().getIdLong();
                long channelId = channel.getIdLong();
                channel.sendMessage("<@&" + session.groupRoleId() + ">")
                        .queue(msg -> signupService.savePingMessage(signupId, guildId, channelId, msg.getIdLong()));

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

        try {
            handleModal(event, modalId);
        } catch (Exception e) {
            log.error("Unhandled exception in signup modal interaction '{}'", modalId, e);
            try {
                if (!event.isAcknowledged()) {
                    event.reply("An unexpected error occurred. Please try again or contact an admin.")
                            .setEphemeral(true).queue();
                }
            } catch (Exception ignored) {}
        }
    }

    private void handleModal(ModalInteractionEvent event, String modalId) {
        if (modalId.startsWith("signup_builder_")) {
            handleBuilderModal(event, modalId);
            return;
        }
        if (modalId.startsWith("signup_hub_")) {
            handleHubModal(event, modalId);
            return;
        }

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

    // --- /devsignups: bulk close (dev only, double-checked here even though @Test already keeps
    // the slash command itself out of production) ---

    private void handleDevCloseAll(ButtonInteractionEvent event, String id) {
        if (botConfig.getLiveEnvironment()) {
            event.reply("This command is dev-only.").setEphemeral(true).queue();
            return;
        }

        switch (id) {
            case "signup_dev_close_all" -> event.reply(
                            "Are you sure? This closes **every active signup on every server** the bot is in.")
                    .setEphemeral(true)
                    .addComponents(ActionRow.of(
                            Button.danger("signup_dev_close_all_confirm", "Yes, close everything"),
                            Button.secondary("signup_dev_close_all_cancel", "Cancel")
                    ))
                    .queue();

            case "signup_dev_close_all_confirm" -> {
                int count = signupService.closeAllActiveSignups(event.getJDA());
                event.editMessage("Closed " + count + " signup(s) across all servers.")
                        .setComponents()
                        .queue();
            }

            case "signup_dev_close_all_cancel" -> event.editMessage("Cancelled.")
                    .setComponents()
                    .delay(Duration.ofSeconds(3))
                    .flatMap(InteractionHook::deleteOriginal)
                    .queue();
        }
    }

    // --- /signup hub: list/post/refresh (queue/group/submission builders reuse signup_builder_* as-is) ---

    private void handleHubButton(ButtonInteractionEvent event, String id) {
        switch (id) {
            case "signup_hub_list:_" -> replySignupList(event);

            case "signup_hub_post:_" -> {
                List<SignupSession> visible = signupService.getVisibleSignups(event.getGuild().getIdLong());
                if (visible.isEmpty()) {
                    event.reply("There are no current signups to post.").setEphemeral(true).queue();
                    return;
                }
                event.replyModal(buildHubPostModal(visible)).queue();
            }

            case "signup_hub_refresh:_" -> {
                Member member = event.getMember();
                if (member == null || !adminRoleFilter.isAuthorized(event.getGuild(), member)) {
                    event.reply("You need the Admin role (or higher) to use this.").setEphemeral(true).queue();
                    return;
                }
                refreshAllPanels(event, event.getGuild());
            }
        }
    }

    private void handleHubModal(ModalInteractionEvent event, String modalId) {
        if (!modalId.equals("signup_hub_post_modal:_")) return;

        SignupPanelType panelType = SignupPanelType.valueOf(
                event.getValue("signup_hub_post_type").getAsStringList().getFirst());
        long signupId = Long.parseLong(event.getValue("signup_hub_post_signup").getAsStringList().getFirst());

        Guild guild = event.getGuild();
        var channelMapping = event.getValue("signup_hub_post_channel");
        TextChannel channel = (channelMapping != null && !channelMapping.getAsLongList().isEmpty())
                ? guild.getTextChannelById(channelMapping.getAsLongList().getFirst())
                : event.getChannel().asTextChannel();

        if (channel == null) {
            event.reply("That channel isn't a usable text channel.").setEphemeral(true).queue();
            return;
        }

        try {
            signupService.postSignupEmbed(guild, channel, signupId, panelType);
            event.reply("Posted `" + panelType + "` signup panel.")
                    .setEphemeral(true)
                    .delay(Duration.ofSeconds(5))
                    .flatMap(InteractionHook::deleteOriginal)
                    .queue();
        } catch (IllegalArgumentException e) {
            event.reply("That signup no longer exists.")
                    .setEphemeral(true)
                    .delay(Duration.ofSeconds(5))
                    .flatMap(InteractionHook::deleteOriginal)
                    .queue();
        }
    }

    private void replySignupList(ButtonInteractionEvent event) {
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
                description.append("*...and more.*\n");
                break;
            }

            description.append(entry);
        }

        if (signups.size() > MAX_LIST_ENTRIES) {
            description.append("*Showing ").append(MAX_LIST_ENTRIES)
                    .append(" of ").append(signups.size()).append(" signups.*");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Current Signups")
                .setDescription(description.toString())
                .setColor(Color.BLUE);

        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    private void refreshAllPanels(ButtonInteractionEvent event, Guild guild) {
        List<SignupSession> signups = signupService.getVisibleSignups(guild.getIdLong());

        if (signups.isEmpty()) {
            event.reply("No active signup panels to update.")
                    .setEphemeral(true)
                    .delay(Duration.ofSeconds(5))
                    .flatMap(InteractionHook::deleteOriginal)
                    .queue();
            return;
        }

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

    /** Signup titles double as option labels — capped at Discord's 25-option-per-select limit. */
    private Modal buildHubPostModal(List<SignupSession> visible) {
        StringSelectMenu typeSelect = StringSelectMenu.create("signup_hub_post_type")
                .addOption("Public", SignupPanelType.PUBLIC.name())
                .addOption("Admin", SignupPanelType.ADMIN.name())
                .setRequiredRange(1, 1)
                .build();

        StringSelectMenu.Builder signupSelectBuilder = StringSelectMenu.create("signup_hub_post_signup")
                .setRequiredRange(1, 1)
                .setPlaceholder("Which signup?");

        int limit = Math.min(visible.size(), SelectMenu.OPTIONS_MAX_AMOUNT);
        for (int i = 0; i < limit; i++) {
            SignupSession signup = visible.get(i);
            String label = "#" + signup.signupId() + " — " + truncate(signup.title(), 70) + " (" + signup.type() + ")";
            signupSelectBuilder.addOption(truncate(label, 100), String.valueOf(signup.signupId()));
        }

        EntitySelectMenu channelSelect = EntitySelectMenu.create("signup_hub_post_channel", EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setRequired(false)
                .setRequiredRange(0, 1)
                .setPlaceholder("Defaults to this channel if left blank")
                .build();

        return Modal.create("signup_hub_post_modal:_", "Post a Signup Panel")
                .addComponents(
                        Label.of("Panel type", typeSelect),
                        Label.of("Signup", signupSelectBuilder.build()),
                        Label.of("Channel (optional)", channelSelect)
                )
                .build();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // --- /signupbuilder: buttons ---

    private void handleBuilderButton(ButtonInteractionEvent event, String id) {
        String action = id.split(":")[0];

        switch (action) {
            case "signup_builder_type" -> event.replyModal(buildTypeModal(id.split(":")[1])).queue();

            case "signup_builder_add_field" -> {
                long userId = event.getUser().getIdLong();
                if (signupService.getSubmissionDraft(userId) == null) {
                    event.reply("This builder session has expired. Start over with `/signupbuilder`.")
                            .setEphemeral(true).queue();
                    return;
                }

                event.replyModal(buildFieldModal()).queue();
            }

            case "signup_builder_finish" -> {
                long userId = event.getUser().getIdLong();
                SubmissionDraft draft = signupService.getSubmissionDraft(userId);

                if (draft == null) {
                    event.editMessage("This builder session has expired. Start over with `/signupbuilder`.")
                            .setComponents()
                            .delay(Duration.ofSeconds(5))
                            .flatMap(InteractionHook::deleteOriginal)
                            .queue();
                    return;
                }

                Guild guild = event.getGuild();
                TextChannel adminChannel = guild.getTextChannelById(draft.adminChannelId());
                TextChannel publicChannel = guild.getTextChannelById(draft.publicChannelId());

                if (adminChannel == null || publicChannel == null) {
                    event.editMessage("One of the selected channels no longer exists. Start over with `/signupbuilder`.")
                            .setComponents()
                            .delay(Duration.ofSeconds(5))
                            .flatMap(InteractionHook::deleteOriginal)
                            .queue();
                    signupService.cancelSubmissionDraft(userId);
                    return;
                }

                signupService.createSubmissionSession(guild, publicChannel, adminChannel, draft.title(),
                        draft.fields(), draft.maxEntries(), userId);
                signupService.cancelSubmissionDraft(userId);

                event.editMessage("Submission signup **" + draft.title() + "** created with "
                                + draft.fields().size() + " field(s).")
                        .setComponents()
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }

            case "signup_builder_cancel" -> {
                signupService.cancelSubmissionDraft(event.getUser().getIdLong());
                event.editMessage("Cancelled.")
                        .setComponents()
                        .delay(Duration.ofSeconds(3))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }
        }
    }

    // --- /signupbuilder: modals ---

    private void handleBuilderModal(ModalInteractionEvent event, String modalId) {
        String action = modalId.split(":")[0];

        switch (action) {
            case "signup_builder_submit" -> {
                String type = modalId.split(":")[1];
                String title = event.getValue("signup_builder_title").getAsString().trim();
                Guild guild = event.getGuild();
                long userId = event.getUser().getIdLong();

                long adminChannelId = event.getValue("signup_builder_admin_channel").getAsLongList().getFirst();
                long publicChannelId = event.getValue("signup_builder_public_channel").getAsLongList().getFirst();
                TextChannel adminChannel = guild.getTextChannelById(adminChannelId);
                TextChannel publicChannel = guild.getTextChannelById(publicChannelId);

                if (adminChannel == null || publicChannel == null) {
                    event.reply("One of the selected channels isn't a usable text channel — try again.")
                            .setEphemeral(true)
                            .delay(Duration.ofSeconds(5))
                            .flatMap(InteractionHook::deleteOriginal)
                            .queue();
                    return;
                }

                // The type modal is always opened from a button on the /signup hub message, so
                // editMessage() here edits *that* message in place — no need to track its ID
                // ourselves (ModalInteractionEvent#getMessage() carries it automatically for any
                // modal opened from a component).
                switch (type) {
                    case "QUEUE" -> {
                        String notify = event.getValue("signup_builder_notify").getAsString().trim();
                        Integer max = parseOptionalPositiveInt(event, "signup_builder_max");

                        if (max == INVALID_NUMBER) {
                            replyInvalidNumber(event);
                            return;
                        }

                        signupService.createQueueSession(guild, publicChannel, adminChannel, title, notify, max, userId);
                        event.editMessage("Queue signup **" + title + "** created.")
                                .setComponents()
                                .delay(Duration.ofSeconds(5))
                                .flatMap(InteractionHook::deleteOriginal)
                                .queue();
                    }

                    case "GROUP" -> {
                        signupService.createGroupSession(guild, publicChannel, adminChannel, title, userId);
                        event.editMessage("Group signup **" + title + "** created. A role is being set up.")
                                .setComponents()
                                .delay(Duration.ofSeconds(5))
                                .flatMap(InteractionHook::deleteOriginal)
                                .queue();
                    }

                    case "SUBMISSION" -> {
                        Integer max = parseOptionalPositiveInt(event, "signup_builder_max");

                        if (max == INVALID_NUMBER) {
                            replyInvalidNumber(event);
                            return;
                        }

                        signupService.startSubmissionDraft(userId, guild.getIdLong(),
                                adminChannelId, publicChannelId, title, max);
                        SubmissionDraft draft = signupService.getSubmissionDraft(userId);

                        event.editMessage(renderDraftSummary(draft))
                                .setComponents(ActionRow.of(draftButtons(draft)))
                                .queue();
                    }
                }
            }

            case "signup_builder_field_submit" -> {
                long userId = event.getUser().getIdLong();

                String label = event.getValue("signup_builder_field_label").getAsString().trim();
                String type = event.getValue("signup_builder_field_type").getAsStringList().getFirst();
                boolean required = "YES".equals(event.getValue("signup_builder_field_required").getAsStringList().getFirst());

                SubmissionField field = new SubmissionField(label, type, required);

                boolean added = signupService.addDraftField(userId, field);
                if (!added) {
                    event.reply("This builder session has expired, or already has the maximum of 3 fields.")
                            .setEphemeral(true).queue();
                    return;
                }

                // Same as above: this modal was opened from the "Add Field" button on the
                // Submission Builder status message, so editMessage() updates that exact message.
                SubmissionDraft draft = signupService.getSubmissionDraft(userId);
                event.editMessage(renderDraftSummary(draft))
                        .setComponents(ActionRow.of(draftButtons(draft)))
                        .queue();
            }
        }
    }

    private static final Integer INVALID_NUMBER = Integer.MIN_VALUE;

    /** Returns {@code null} for blank input (unlimited), the parsed value, or {@link #INVALID_NUMBER} on bad input. */
    private Integer parseOptionalPositiveInt(ModalInteractionEvent event, String inputId) {
        var value = event.getValue(inputId);
        if (value == null) return null;

        String raw = value.getAsString().trim();
        if (raw.isBlank()) return null;

        try {
            int parsed = Integer.parseInt(raw);
            return parsed > 0 ? parsed : INVALID_NUMBER;
        } catch (NumberFormatException e) {
            return INVALID_NUMBER;
        }
    }

    private void replyInvalidNumber(ModalInteractionEvent event) {
        event.reply("That number field must be a whole number greater than 0, or left blank for unlimited.")
                .setEphemeral(true)
                .delay(Duration.ofSeconds(5))
                .flatMap(InteractionHook::deleteOriginal)
                .queue();
    }

    private String renderDraftSummary(SubmissionDraft draft) {
        StringBuilder sb = new StringBuilder("**Submission Builder — ").append(draft.title()).append("**\n\n");

        if (draft.fields().isEmpty()) {
            sb.append("*No fields yet — add at least one.*");
        } else {
            for (int i = 0; i < draft.fields().size(); i++) {
                SubmissionField field = draft.fields().get(i);
                sb.append(i + 1).append(". **").append(field.label()).append("** (")
                        .append(field.type()).append(field.required() ? ", required" : ", optional").append(")\n");
            }
        }

        return sb.toString();
    }

    private List<Button> draftButtons(SubmissionDraft draft) {
        List<Button> buttons = new ArrayList<>();

        if (draft.fields().size() < 3) {
            buttons.add(Button.primary("signup_builder_add_field:_", "Add Field"));
        }
        if (!draft.fields().isEmpty()) {
            buttons.add(Button.success("signup_builder_finish:_", "Create Signup"));
        }
        buttons.add(Button.danger("signup_builder_cancel:_", "Cancel"));

        return buttons;
    }

    /** Both channels are always explicit picks — never implicitly "wherever /signupbuilder was run". */
    private Label adminChannelSelect() {
        return Label.of("Admin channel", EntitySelectMenu.create("signup_builder_admin_channel", EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder("Where admin controls get posted")
                .setRequiredRange(1, 1)
                .build());
    }

    private Label publicChannelSelect() {
        return Label.of("Public channel", EntitySelectMenu.create("signup_builder_public_channel", EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder("Where the public signup panel gets posted")
                .setRequiredRange(1, 1)
                .build());
    }

    private Modal buildTypeModal(String type) {
        TextInput titleInput = TextInput.create("signup_builder_title", TextInputStyle.SHORT)
                .setPlaceholder("Signup title")
                .setRequired(true)
                .setRequiredRange(1, 100)
                .build();

        return switch (type) {
            case "QUEUE" -> {
                TextInput notifyInput = TextInput.create("signup_builder_notify", TextInputStyle.PARAGRAPH)
                        .setPlaceholder("Message sent when a user reaches the front of the queue")
                        .setRequired(true)
                        .setRequiredRange(1, 300)
                        .build();
                TextInput maxInput = TextInput.create("signup_builder_max", TextInputStyle.SHORT)
                        .setPlaceholder("Max signups — leave blank for unlimited")
                        .setRequired(false)
                        .setRequiredRange(0, 10)
                        .build();

                // Exactly 5 of 5 components Discord allows in one modal — no room to spare here.
                yield Modal.create("signup_builder_submit:QUEUE", "New Queue Signup")
                        .addComponents(
                                Label.of("Title", titleInput),
                                Label.of("Notification message", notifyInput),
                                Label.of("Max signups (optional)", maxInput),
                                adminChannelSelect(),
                                publicChannelSelect()
                        )
                        .build();
            }

            case "GROUP" -> Modal.create("signup_builder_submit:GROUP", "New Group Signup")
                    .addComponents(
                            Label.of("Title", titleInput),
                            adminChannelSelect(),
                            publicChannelSelect()
                    )
                    .build();

            case "SUBMISSION" -> {
                TextInput maxInput = TextInput.create("signup_builder_max", TextInputStyle.SHORT)
                        .setPlaceholder("Max submissions — leave blank for unlimited")
                        .setRequired(false)
                        .setRequiredRange(0, 10)
                        .build();

                yield Modal.create("signup_builder_submit:SUBMISSION", "New Submission Signup")
                        .addComponents(
                                Label.of("Title", titleInput),
                                Label.of("Max submissions (optional)", maxInput),
                                adminChannelSelect(),
                                publicChannelSelect()
                        )
                        .build();
            }

            default -> throw new IllegalStateException("Unknown signup builder type: " + type);
        };
    }

    private Modal buildFieldModal() {
        TextInput labelInput = TextInput.create("signup_builder_field_label", TextInputStyle.SHORT)
                .setPlaceholder("e.g. Movie, Song, Idea")
                .setRequired(true)
                .setRequiredRange(1, 45)
                .build();

        StringSelectMenu typeSelect = StringSelectMenu.create("signup_builder_field_type")
                .addOption("Text", SubmissionField.TYPE_TEXT)
                .addOption("Link", SubmissionField.TYPE_LINK)
                .addOption("Image", SubmissionField.TYPE_IMAGE)
                .setDefaultValues(SubmissionField.TYPE_TEXT)
                .setRequiredRange(1, 1)
                .build();

        StringSelectMenu requiredSelect = StringSelectMenu.create("signup_builder_field_required")
                .addOption("Yes", "YES")
                .addOption("No", "NO")
                .setDefaultValues("YES")
                .setRequiredRange(1, 1)
                .build();

        return Modal.create("signup_builder_field_submit:_", "Add Field")
                .addComponents(
                        Label.of("Field label", labelInput),
                        Label.of("Type", typeSelect),
                        Label.of("Required?", requiredSelect)
                )
                .build();
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

        signupService.deletePingMessages(event.getGuild(), signupId);

        long guildId = event.getGuild().getIdLong();
        long channelId = channel.getIdLong();
        channel.sendMessage("<@" + newFirst.userId() + ">, " + session.notificationMessage())
                .queue(msg -> signupService.savePingMessage(signupId, guildId, channelId, msg.getIdLong()));
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
