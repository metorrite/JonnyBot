package com.younglings.bot.commands.signup;

import com.younglings.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.SelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
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

/**
 * {@code signupv2_}-prefixed handlers for {@link SignupHubV2Command} — the Components V2 preview
 * of the signup hub. Deliberately its own ID namespace and listener rather than touching
 * {@link SignupInteractionListener}, so the real {@code /signup} is completely unaffected; this
 * reuses {@link SignupService} directly for every piece of actual business logic (session
 * creation, draft state, panel posting), so nothing about *what* the bot does is duplicated —
 * only *how the response looks* changes. Every reply here is a {@link Container}; see
 * {@link #infoContainer} for the shared one-line/multi-line "toast" shape used for the many small
 * confirmations that used to be bare {@code event.reply("some string")} text.
 * <p>
 * Out of scope (see {@link SignupHubV2Command}'s class doc): the posted Queue/Group/Submission
 * panels themselves and their admin controls — those aren't part of the hub and stay on the
 * existing embed-based design for now.
 */
@BService
public class SignupHubV2InteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(SignupHubV2InteractionListener.class);
    private static final int MAX_LIST_ENTRIES = 20;
    private static final int LIST_TEXT_LIMIT = 3800;

    private static final Color ACCENT_PRIMARY = Color.CYAN;
    private static final Color ACCENT_INFO = Color.BLUE;
    private static final Color ACCENT_SUCCESS = new Color(0x57F287);
    private static final Color ACCENT_WARNING = new Color(0xFEE75C);
    private static final Color ACCENT_DANGER = new Color(0xED4245);

    private final SignupService signupService;
    private final AdminRoleFilter adminRoleFilter;

    public SignupHubV2InteractionListener(SignupService signupService, AdminRoleFilter adminRoleFilter) {
        this.signupService = signupService;
        this.adminRoleFilter = adminRoleFilter;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null) return;
        String id = event.getComponentId();
        if (!id.startsWith("signupv2_") || !id.contains(":")) return;

        try {
            handleButton(event, id);
        } catch (Exception e) {
            log.error("Unhandled exception in signupv2 button interaction '{}'", id, e);
            replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getGuild() == null) return;
        String modalId = event.getModalId();
        if (!modalId.startsWith("signupv2_") || !modalId.contains(":")) return;

        try {
            handleModal(event, modalId);
        } catch (Exception e) {
            log.error("Unhandled exception in signupv2 modal interaction '{}'", modalId, e);
            replyError(event);
        }
    }

    private void handleButton(ButtonInteractionEvent event, String id) {
        if (id.startsWith("signupv2_builder_")) {
            handleBuilderButton(event, id);
            return;
        }

        switch (id) {
            case "signupv2_hub_list:_" -> replySignupList(event);

            case "signupv2_hub_post:_" -> {
                List<SignupSession> visible = signupService.getVisibleSignups(event.getGuild().getIdLong());
                if (visible.isEmpty()) {
                    event.replyComponents(List.of(infoContainer(ACCENT_WARNING, "There are no current signups to post.")))
                            .useComponentsV2(true).setEphemeral(true).queue();
                    return;
                }
                event.replyModal(buildHubPostModal(visible)).queue();
            }

            case "signupv2_hub_refresh:_" -> {
                Member member = event.getMember();
                if (member == null || !adminRoleFilter.isAuthorized(event.getGuild(), member)) {
                    event.replyComponents(List.of(infoContainer(ACCENT_WARNING, "You need the Admin role (or higher) to use this.")))
                            .useComponentsV2(true).setEphemeral(true).queue();
                    return;
                }
                refreshAllPanels(event, event.getGuild());
            }
        }
    }

    private void handleBuilderButton(ButtonInteractionEvent event, String id) {
        String action = id.split(":")[0];

        switch (action) {
            case "signupv2_builder_type" -> event.replyModal(buildTypeModal(id.split(":")[1])).queue();

            case "signupv2_builder_add_field" -> {
                long userId = event.getUser().getIdLong();
                if (signupService.getSubmissionDraft(userId) == null) {
                    event.replyComponents(List.of(infoContainer(ACCENT_WARNING,
                                    "This builder session has expired. Start over with `/signupv2`.")))
                            .useComponentsV2(true).setEphemeral(true).queue();
                    return;
                }
                event.replyModal(buildFieldModal()).queue();
            }

            case "signupv2_builder_finish" -> {
                long userId = event.getUser().getIdLong();
                SubmissionDraft draft = signupService.getSubmissionDraft(userId);

                if (draft == null) {
                    editToToastThenDelete(event, ACCENT_WARNING,
                            "This builder session has expired. Start over with `/signupv2`.");
                    return;
                }

                Guild guild = event.getGuild();
                TextChannel adminChannel = guild.getTextChannelById(draft.adminChannelId());
                TextChannel publicChannel = guild.getTextChannelById(draft.publicChannelId());

                if (adminChannel == null || publicChannel == null) {
                    editToToastThenDelete(event, ACCENT_WARNING,
                            "One of the selected channels no longer exists. Start over with `/signupv2`.");
                    signupService.cancelSubmissionDraft(userId);
                    return;
                }

                signupService.createSubmissionSession(guild, publicChannel, adminChannel, draft.title(),
                        draft.fields(), draft.maxEntries(), userId);
                signupService.cancelSubmissionDraft(userId);

                editToToastThenDelete(event, ACCENT_SUCCESS, "Submission signup **" + draft.title() + "** created with "
                        + draft.fields().size() + " field(s).");
            }

            case "signupv2_builder_cancel" -> {
                signupService.cancelSubmissionDraft(event.getUser().getIdLong());
                editToToastThenDelete(event, ACCENT_WARNING, "Cancelled.");
            }
        }
    }

    private void handleModal(ModalInteractionEvent event, String modalId) {
        if (modalId.startsWith("signupv2_builder_")) {
            handleBuilderModal(event, modalId);
            return;
        }
        if (modalId.equals("signupv2_hub_post_modal:_")) {
            handleHubPostModal(event);
        }
    }

    private void handleHubPostModal(ModalInteractionEvent event) {
        SignupPanelType panelType = SignupPanelType.valueOf(
                event.getValue("signupv2_hub_post_type").getAsStringList().getFirst());
        long signupId = Long.parseLong(event.getValue("signupv2_hub_post_signup").getAsStringList().getFirst());

        Guild guild = event.getGuild();
        var channelMapping = event.getValue("signupv2_hub_post_channel");
        TextChannel channel = (channelMapping != null && !channelMapping.getAsLongList().isEmpty())
                ? guild.getTextChannelById(channelMapping.getAsLongList().getFirst())
                : event.getChannel().asTextChannel();

        if (channel == null) {
            event.replyComponents(List.of(infoContainer(ACCENT_WARNING, "That channel isn't a usable text channel.")))
                    .useComponentsV2(true).setEphemeral(true).queue();
            return;
        }

        try {
            signupService.postSignupEmbed(guild, channel, signupId, panelType);
            replyToastThenDelete(event, ACCENT_SUCCESS, "Posted `" + panelType + "` signup panel.");
        } catch (IllegalArgumentException e) {
            replyToastThenDelete(event, ACCENT_WARNING, "That signup no longer exists.");
        }
    }

    private void handleBuilderModal(ModalInteractionEvent event, String modalId) {
        String action = modalId.split(":")[0];

        switch (action) {
            case "signupv2_builder_submit" -> {
                String type = modalId.split(":")[1];
                String title = event.getValue("signupv2_builder_title").getAsString().trim();
                Guild guild = event.getGuild();
                long userId = event.getUser().getIdLong();

                long adminChannelId = event.getValue("signupv2_builder_admin_channel").getAsLongList().getFirst();
                long publicChannelId = event.getValue("signupv2_builder_public_channel").getAsLongList().getFirst();
                TextChannel adminChannel = guild.getTextChannelById(adminChannelId);
                TextChannel publicChannel = guild.getTextChannelById(publicChannelId);

                if (adminChannel == null || publicChannel == null) {
                    replyToastThenDelete(event, ACCENT_WARNING, "One of the selected channels isn't a usable text channel — try again.");
                    return;
                }

                switch (type) {
                    case "QUEUE" -> {
                        String notify = event.getValue("signupv2_builder_notify").getAsString().trim();
                        Integer max = parseOptionalPositiveInt(event, "signupv2_builder_max");

                        if (max == INVALID_NUMBER) {
                            replyInvalidNumber(event);
                            return;
                        }

                        signupService.createQueueSession(guild, publicChannel, adminChannel, title, notify, max, userId);
                        editToToastThenDelete(event, ACCENT_SUCCESS, "Queue signup **" + title + "** created.");
                    }

                    case "GROUP" -> {
                        signupService.createGroupSession(guild, publicChannel, adminChannel, title, userId);
                        editToToastThenDelete(event, ACCENT_SUCCESS, "Group signup **" + title + "** created. A role is being set up.");
                    }

                    case "SUBMISSION" -> {
                        Integer max = parseOptionalPositiveInt(event, "signupv2_builder_max");

                        if (max == INVALID_NUMBER) {
                            replyInvalidNumber(event);
                            return;
                        }

                        signupService.startSubmissionDraft(userId, guild.getIdLong(),
                                adminChannelId, publicChannelId, title, max);
                        SubmissionDraft draft = signupService.getSubmissionDraft(userId);

                        event.editComponents(List.of(draftContainer(draft)))
                                .useComponentsV2(true)
                                .queue();
                    }
                }
            }

            case "signupv2_builder_field_submit" -> {
                long userId = event.getUser().getIdLong();

                String label = event.getValue("signupv2_builder_field_label").getAsString().trim();
                String type = event.getValue("signupv2_builder_field_type").getAsStringList().getFirst();
                boolean required = "YES".equals(event.getValue("signupv2_builder_field_required").getAsStringList().getFirst());

                SubmissionField field = new SubmissionField(label, type, required);

                boolean added = signupService.addDraftField(userId, field);
                if (!added) {
                    event.replyComponents(List.of(infoContainer(ACCENT_WARNING,
                                    "This builder session has expired, or already has the maximum of 3 fields.")))
                            .useComponentsV2(true).setEphemeral(true).queue();
                    return;
                }

                SubmissionDraft draft = signupService.getSubmissionDraft(userId);
                event.editComponents(List.of(draftContainer(draft)))
                        .useComponentsV2(true)
                        .queue();
            }
        }
    }

    // --- List / refresh ---

    private void replySignupList(ButtonInteractionEvent event) {
        List<SignupSession> signups = signupService.getVisibleSignups(event.getGuild().getIdLong());

        if (signups.isEmpty()) {
            event.replyComponents(List.of(infoContainer(ACCENT_INFO, "There are no current signups.")))
                    .useComponentsV2(true).setEphemeral(true).queue();
            return;
        }

        List<SignupSession> page = signups.size() > MAX_LIST_ENTRIES
                ? signups.subList(0, MAX_LIST_ENTRIES)
                : signups;

        StringBuilder text = new StringBuilder();
        for (SignupSession signup : page) {
            String status = signupService.getSignupStatus(signup.signupId());
            String entry = "**" + signup.signupId() + "** — " + signup.title()
                    + "\nType: `" + signup.type().name() + "` • Status: `" + status + "`\n\n";

            if (text.length() + entry.length() > LIST_TEXT_LIMIT) {
                text.append("*...and more.*\n");
                break;
            }
            text.append(entry);
        }

        if (signups.size() > MAX_LIST_ENTRIES) {
            text.append("*Showing ").append(MAX_LIST_ENTRIES).append(" of ").append(signups.size()).append(" signups.*");
        }

        Container container = Container.of(
                TextDisplay.of("# Current Signups"),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of(text.toString())
        ).withAccentColor(ACCENT_INFO);

        event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void refreshAllPanels(ButtonInteractionEvent event, Guild guild) {
        List<SignupSession> signups = signupService.getVisibleSignups(guild.getIdLong());

        if (signups.isEmpty()) {
            replyToastThenDelete(event, ACCENT_INFO, "No active signup panels to update.");
            return;
        }

        int count = signups.size();
        for (SignupSession signup : signups) {
            signupService.updateMessages(guild, signup.signupId());
        }

        replyToastThenDelete(event, ACCENT_SUCCESS,
                "Refreshing " + count + " signup panel" + (count == 1 ? "" : "s") + ". Changes will appear shortly.");
    }

    /** Signup titles double as option labels — capped at Discord's 25-option-per-select limit. */
    private Modal buildHubPostModal(List<SignupSession> visible) {
        StringSelectMenu typeSelect = StringSelectMenu.create("signupv2_hub_post_type")
                .addOption("Public", SignupPanelType.PUBLIC.name())
                .addOption("Admin", SignupPanelType.ADMIN.name())
                .setRequiredRange(1, 1)
                .build();

        StringSelectMenu.Builder signupSelectBuilder = StringSelectMenu.create("signupv2_hub_post_signup")
                .setRequiredRange(1, 1)
                .setPlaceholder("Which signup?");

        int limit = Math.min(visible.size(), SelectMenu.OPTIONS_MAX_AMOUNT);
        for (int i = 0; i < limit; i++) {
            SignupSession signup = visible.get(i);
            String label = "#" + signup.signupId() + " — " + truncate(signup.title(), 70) + " (" + signup.type() + ")";
            signupSelectBuilder.addOption(truncate(label, 100), String.valueOf(signup.signupId()));
        }

        EntitySelectMenu channelSelect = EntitySelectMenu.create("signupv2_hub_post_channel", EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setRequired(false)
                .setRequiredRange(0, 1)
                .setPlaceholder("Defaults to this channel if left blank")
                .build();

        return Modal.create("signupv2_hub_post_modal:_", "Post a Signup Panel")
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

    // --- Builder modals (identical shape to SignupInteractionListener's, signupv2_-prefixed) ---

    private Label adminChannelSelect() {
        return Label.of("Admin channel", EntitySelectMenu.create("signupv2_builder_admin_channel", EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder("Where admin controls get posted")
                .setRequiredRange(1, 1)
                .build());
    }

    private Label publicChannelSelect() {
        return Label.of("Public channel", EntitySelectMenu.create("signupv2_builder_public_channel", EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder("Where the public signup panel gets posted")
                .setRequiredRange(1, 1)
                .build());
    }

    private Modal buildTypeModal(String type) {
        TextInput titleInput = TextInput.create("signupv2_builder_title", TextInputStyle.SHORT)
                .setPlaceholder("Signup title")
                .setRequired(true)
                .setRequiredRange(1, 100)
                .build();

        return switch (type) {
            case "QUEUE" -> {
                TextInput notifyInput = TextInput.create("signupv2_builder_notify", TextInputStyle.PARAGRAPH)
                        .setPlaceholder("Message sent when a user reaches the front of the queue")
                        .setRequired(true)
                        .setRequiredRange(1, 300)
                        .build();
                TextInput maxInput = TextInput.create("signupv2_builder_max", TextInputStyle.SHORT)
                        .setPlaceholder("Max signups — leave blank for unlimited")
                        .setRequired(false)
                        .setRequiredRange(0, 10)
                        .build();

                yield Modal.create("signupv2_builder_submit:QUEUE", "New Queue Signup")
                        .addComponents(
                                Label.of("Title", titleInput),
                                Label.of("Notification message", notifyInput),
                                Label.of("Max signups (optional)", maxInput),
                                adminChannelSelect(),
                                publicChannelSelect()
                        )
                        .build();
            }

            case "GROUP" -> Modal.create("signupv2_builder_submit:GROUP", "New Group Signup")
                    .addComponents(
                            Label.of("Title", titleInput),
                            adminChannelSelect(),
                            publicChannelSelect()
                    )
                    .build();

            case "SUBMISSION" -> {
                TextInput maxInput = TextInput.create("signupv2_builder_max", TextInputStyle.SHORT)
                        .setPlaceholder("Max submissions — leave blank for unlimited")
                        .setRequired(false)
                        .setRequiredRange(0, 10)
                        .build();

                yield Modal.create("signupv2_builder_submit:SUBMISSION", "New Submission Signup")
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
        TextInput labelInput = TextInput.create("signupv2_builder_field_label", TextInputStyle.SHORT)
                .setPlaceholder("e.g. Movie, Song, Idea")
                .setRequired(true)
                .setRequiredRange(1, 45)
                .build();

        StringSelectMenu typeSelect = StringSelectMenu.create("signupv2_builder_field_type")
                .addOption("Text", SubmissionField.TYPE_TEXT)
                .addOption("Link", SubmissionField.TYPE_LINK)
                .addOption("Image", SubmissionField.TYPE_IMAGE)
                .setDefaultValues(SubmissionField.TYPE_TEXT)
                .setRequiredRange(1, 1)
                .build();

        StringSelectMenu requiredSelect = StringSelectMenu.create("signupv2_builder_field_required")
                .addOption("Yes", "YES")
                .addOption("No", "NO")
                .setDefaultValues("YES")
                .setRequiredRange(1, 1)
                .build();

        return Modal.create("signupv2_builder_field_submit:_", "Add Field")
                .addComponents(
                        Label.of("Field label", labelInput),
                        Label.of("Type", typeSelect),
                        Label.of("Required?", requiredSelect)
                )
                .build();
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
        replyToastThenDelete(event, ACCENT_WARNING,
                "That number field must be a whole number greater than 0, or left blank for unlimited.");
    }

    // --- Draft status card ---

    private Container draftContainer(SubmissionDraft draft) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("**Submission Builder — " + draft.title() + "**"));

        if (draft.fields().isEmpty()) {
            children.add(TextDisplay.of("*No fields yet — add at least one.*"));
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < draft.fields().size(); i++) {
                SubmissionField field = draft.fields().get(i);
                sb.append(i + 1).append(". **").append(field.label()).append("** (")
                        .append(field.type()).append(field.required() ? ", required" : ", optional").append(")\n");
            }
            children.add(TextDisplay.of(sb.toString()));
        }

        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(ActionRow.of(draftButtons(draft)));

        return Container.of(children).withAccentColor(ACCENT_PRIMARY);
    }

    private List<Button> draftButtons(SubmissionDraft draft) {
        List<Button> buttons = new ArrayList<>();

        if (draft.fields().size() < 3) {
            buttons.add(Button.primary("signupv2_builder_add_field:_", "Add Field"));
        }
        if (!draft.fields().isEmpty()) {
            buttons.add(Button.success("signupv2_builder_finish:_", "Create Signup"));
        }
        buttons.add(Button.danger("signupv2_builder_cancel:_", "Cancel"));

        return buttons;
    }

    // --- Shared "toast" container + helpers ---

    private Container infoContainer(Color accent, String... lines) {
        List<ContainerChildComponent> children = new ArrayList<>();
        for (String line : lines) children.add(TextDisplay.of(line));
        return Container.of(children).withAccentColor(accent);
    }

    /** Ephemeral reply as a small container, auto-deleted after 5s — mirrors the old "toast" reply-then-delete pattern. */
    private void replyToastThenDelete(ModalInteractionEvent event, Color accent, String text) {
        event.replyComponents(List.of(infoContainer(accent, text)))
                .useComponentsV2(true)
                .setEphemeral(true)
                .delay(Duration.ofSeconds(5))
                .flatMap(InteractionHook::deleteOriginal)
                .queue();
    }

    private void replyToastThenDelete(ButtonInteractionEvent event, Color accent, String text) {
        event.replyComponents(List.of(infoContainer(accent, text)))
                .useComponentsV2(true)
                .setEphemeral(true)
                .delay(Duration.ofSeconds(5))
                .flatMap(InteractionHook::deleteOriginal)
                .queue();
    }

    /** Edits the message a modal/button originated from to a small container, then deletes it after 5s. */
    private void editToToastThenDelete(ModalInteractionEvent event, Color accent, String text) {
        event.editComponents(List.of(infoContainer(accent, text)))
                .useComponentsV2(true)
                .delay(Duration.ofSeconds(5))
                .flatMap(InteractionHook::deleteOriginal)
                .queue();
    }

    private void editToToastThenDelete(ButtonInteractionEvent event, Color accent, String text) {
        event.editComponents(List.of(infoContainer(accent, text)))
                .useComponentsV2(true)
                .delay(Duration.ofSeconds(5))
                .flatMap(InteractionHook::deleteOriginal)
                .queue();
    }

    private void replyError(ButtonInteractionEvent event) {
        try {
            if (!event.isAcknowledged()) {
                event.replyComponents(List.of(infoContainer(ACCENT_DANGER, "An unexpected error occurred. Please try again or contact an admin.")))
                        .useComponentsV2(true).setEphemeral(true).queue();
            }
        } catch (Exception ignored) {}
    }

    private void replyError(ModalInteractionEvent event) {
        try {
            if (!event.isAcknowledged()) {
                event.replyComponents(List.of(infoContainer(ACCENT_DANGER, "An unexpected error occurred. Please try again or contact an admin.")))
                        .useComponentsV2(true).setEphemeral(true).queue();
            }
        } catch (Exception ignored) {}
    }
}
