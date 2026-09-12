package com.younglings.bot.commands.teamforming;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.components.ComponentInteraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.Duration;
import java.util.List;

@BService
public class TeamformingInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(TeamformingInteractionListener.class);

    private static final Color ADDED_COLOR = new Color(0x2E, 0xCC, 0x71);   // green
    private static final Color REMOVED_COLOR = new Color(0xE7, 0x4C, 0x3C); // red
    private static final Duration CONFIRMATION_LIFETIME = Duration.ofSeconds(30);

    private final TeamformingService teamformingService;

    public TeamformingInteractionListener(TeamformingService teamformingService) {
        this.teamformingService = teamformingService;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (event.getGuild() == null || event.getMember() == null) return;

        if (id.startsWith(TeamformingService.TOGGLE_PREFIX)) {
            handleToggle(event, id);
        } else if (id.equals(TeamformingService.MANAGE_TAGS_BUTTON_ID)) {
            handleOpenRemoveMenu(event);
        } else if (id.equals(TeamformingService.UPDATE_ROLES_BUTTON_ID)) {
            handleUpdateRoles(event);
        }
    }

    private void handleToggle(ButtonInteractionEvent event, String id) {
        String roleName = id.substring(TeamformingService.TOGGLE_PREFIX.length());

        try {
            TeamformingService.BatchResult result = teamformingService.toggleRole(event.getGuild(), event.getMember(), roleName);
            replyWithResult(event, result);
        } catch (Exception e) {
            log.error("Failed to toggle teamforming role '{}' for user {}", roleName, event.getUser().getIdLong(), e);
            replyError(event);
        }
    }

    /** Opens a personalized, ephemeral "pick tags to remove" menu built from the member's actual current roles. */
    private void handleOpenRemoveMenu(ButtonInteractionEvent event) {
        List<String> held = teamformingService.getHeldSectionRoleNames(event.getMember());

        if (held.isEmpty()) {
            event.reply("You don't currently have any teamforming tags.").setEphemeral(true)
                    .delay(CONFIRMATION_LIFETIME).flatMap(InteractionHook::deleteOriginal).queue();
            return;
        }

        StringSelectMenu.Builder menu = StringSelectMenu.create(TeamformingService.REMOVE_SELECT_ID)
                .setPlaceholder("Select tag(s) to remove")
                .setRequiredRange(1, held.size());

        for (String roleName : held) {
            menu.addOption(roleName, roleName);
        }

        event.reply("Pick tags to stage for removal, then click **Update Roles** on the panel to apply.")
                .setEphemeral(true)
                .addComponents(ActionRow.of(menu.build()))
                .queue();
    }

    private void handleUpdateRoles(ButtonInteractionEvent event) {
        if (!teamformingService.hasPendingChanges(event.getUser().getIdLong())) {
            event.reply("You don't have any pending tag changes to apply — pick some tags first.")
                    .setEphemeral(true)
                    .delay(CONFIRMATION_LIFETIME).flatMap(InteractionHook::deleteOriginal).queue();
            return;
        }

        try {
            TeamformingService.BatchResult result = teamformingService.applyPendingChanges(event.getGuild(), event.getMember());
            replyWithResult(event, result);
        } catch (Exception e) {
            log.error("Failed to apply pending teamforming changes for user {}", event.getUser().getIdLong(), e);
            replyError(event);
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String id = event.getComponentId();
        if (event.getGuild() == null || event.getMember() == null) return;

        if (id.startsWith(TeamformingService.SELECT_PREFIX)) {
            handleSectionSelect(event, id);
        } else if (id.equals(TeamformingService.REMOVE_SELECT_ID)) {
            handleRemoveSelect(event);
        }
    }

    /** Stages the picked tags and silently acknowledges — no confirmation message per pick, only on Update Roles. */
    private void handleSectionSelect(StringSelectInteractionEvent event, String id) {
        String sectionKey = id.substring(TeamformingService.SELECT_PREFIX.length());
        TeamformingSection section = TeamformingCatalog.sectionByKey(sectionKey);
        if (section == null) {
            event.reply("This teamforming section no longer exists — ask an admin to re-post the panel.")
                    .setEphemeral(true).queue();
            return;
        }

        try {
            teamformingService.stageAdd(event.getUser().getIdLong(), event.getValues());
            event.deferEdit().queue();
        } catch (Exception e) {
            log.error("Failed to stage teamforming selection for section '{}', user {}",
                    sectionKey, event.getUser().getIdLong(), e);
            replyError(event);
        }
    }

    private void handleRemoveSelect(StringSelectInteractionEvent event) {
        try {
            teamformingService.stageRemove(event.getUser().getIdLong(), event.getValues());
            event.editMessage("Staged for removal: **" + String.join("**, **", event.getValues()) + "**\n" +
                            "Click **Update Roles** on the panel to apply.")
                    .setComponents()
                    .delay(CONFIRMATION_LIFETIME)
                    .flatMap(InteractionHook::deleteOriginal)
                    .queue();
        } catch (Exception e) {
            log.error("Failed to stage teamforming removal for user {}", event.getUser().getIdLong(), e);
            replyError(event);
        }
    }

    // --- Reply building ---

    /** Sends the green-added / red-removed confirmation embed(s) for a completed change, role-mentioning without pinging. */
    private void replyWithResult(IReplyCallback event, TeamformingService.BatchResult result) {
        if (result.isEmpty()) {
            event.reply("No changes were made — you already had everything you selected.")
                    .setEphemeral(true)
                    .delay(CONFIRMATION_LIFETIME).flatMap(InteractionHook::deleteOriginal).queue();
            return;
        }

        if (!result.added().isEmpty()) {
            sendResultEmbed(event, "Added", ADDED_COLOR, result.added(), true);
        }
        if (!result.removed().isEmpty()) {
            sendResultEmbed(event, "Removed", REMOVED_COLOR, result.removed(), result.added().isEmpty());
        }
    }

    /**
     * An interaction can only be replied to once — {@code isFirstReply} picks between
     * {@code event.reply(...)} (the interaction's one reply) and a followup message sent via its
     * hook (for a second embed on the same interaction, e.g. both an "Added" and a "Removed"
     * embed from one Update Roles click).
     */
    private void sendResultEmbed(IReplyCallback event, String verb, Color color, List<Role> roles, boolean isFirstReply) {
        String mentions = roles.stream()
                .map(role -> "<@&" + role.getIdLong() + ">")
                .reduce((a, b) -> a + " " + b)
                .orElse("");
        MessageEmbed embed = new EmbedBuilder().setColor(color).setDescription(verb + " role: " + mentions).build();

        if (isFirstReply) {
            event.replyEmbeds(embed)
                    .setEphemeral(true)
                    .setAllowedMentions(List.of()) // show the role mention without actually pinging it
                    .delay(CONFIRMATION_LIFETIME)
                    .flatMap(InteractionHook::deleteOriginal)
                    .queue();
        } else {
            event.getHook().sendMessageEmbeds(embed)
                    .setEphemeral(true)
                    .setAllowedMentions(List.of())
                    .delay(CONFIRMATION_LIFETIME)
                    .flatMap(message -> event.getHook().deleteMessageById(message.getIdLong()))
                    .queue();
        }
    }

    private void replyError(ComponentInteraction event) {
        try {
            if (!event.isAcknowledged()) {
                event.reply("An unexpected error occurred. Please try again or contact an admin.")
                        .setEphemeral(true).queue();
            }
        } catch (Exception ignored) {}
    }
}
