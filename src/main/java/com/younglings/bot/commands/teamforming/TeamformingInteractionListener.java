package com.younglings.bot.commands.teamforming;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ComponentInteraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class TeamformingInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(TeamformingInteractionListener.class);

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
        }
    }

    private void handleToggle(ButtonInteractionEvent event, String id) {
        String roleName = id.substring(TeamformingService.TOGGLE_PREFIX.length());

        try {
            boolean nowHasRole = teamformingService.toggleRole(event.getGuild(), event.getMember(), roleName);
            String message = nowHasRole
                    ? "You now have the **" + roleName + "** tag."
                    : "The **" + roleName + "** tag has been removed.";
            event.reply(message).setEphemeral(true).queue();
        } catch (Exception e) {
            log.error("Failed to toggle teamforming role '{}' for user {}", roleName, event.getUser().getIdLong(), e);
            replyError(event);
        }
    }

    /** Opens a personalized, ephemeral "pick tags to remove" menu built from the member's actual current roles. */
    private void handleOpenRemoveMenu(ButtonInteractionEvent event) {
        List<String> held = teamformingService.getHeldSectionRoleNames(event.getMember());

        if (held.isEmpty()) {
            event.reply("You don't currently have any teamforming tags to remove.").setEphemeral(true).queue();
            return;
        }

        StringSelectMenu.Builder menu = StringSelectMenu.create(TeamformingService.REMOVE_SELECT_ID)
                .setPlaceholder("Select tag(s) to remove")
                .setRequiredRange(1, held.size());

        for (String roleName : held) {
            menu.addOption(roleName, roleName);
        }

        event.reply("Select which of your current tags to remove:")
                .setEphemeral(true)
                .addComponents(ActionRow.of(menu.build()))
                .queue();
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

    private void handleSectionSelect(StringSelectInteractionEvent event, String id) {
        String sectionKey = id.substring(TeamformingService.SELECT_PREFIX.length());
        TeamformingSection section = TeamformingCatalog.sectionByKey(sectionKey);
        if (section == null) {
            event.reply("This teamforming section no longer exists — ask an admin to re-post the panel.")
                    .setEphemeral(true).queue();
            return;
        }

        try {
            Guild guild = event.getGuild();
            Member member = event.getMember();
            List<String> added = teamformingService.applySelection(guild, member, event.getValues());

            String message = added.isEmpty()
                    ? "You already have all the tag(s) you selected for " + section.title() + "."
                    : "Added: **" + String.join("**, **", added) + "**";
            event.reply(message).setEphemeral(true).queue();
        } catch (Exception e) {
            log.error("Failed to apply teamforming selection for section '{}', user {}",
                    sectionKey, event.getUser().getIdLong(), e);
            replyError(event);
        }
    }

    private void handleRemoveSelect(StringSelectInteractionEvent event) {
        try {
            List<String> removed = teamformingService.removeRoles(event.getGuild(), event.getMember(), event.getValues());
            String message = removed.isEmpty()
                    ? "No tags were removed."
                    : "Removed: **" + String.join("**, **", removed) + "**";
            event.editMessage(message).setComponents().queue();
        } catch (Exception e) {
            log.error("Failed to remove teamforming roles for user {}", event.getUser().getIdLong(), e);
            replyError(event);
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
