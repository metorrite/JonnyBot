package com.younglings.bot.commands.teamforming;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ComponentInteraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        if (!id.startsWith(TeamformingService.TOGGLE_PREFIX)) return;
        if (event.getGuild() == null || event.getMember() == null) return;

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

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(TeamformingService.SELECT_PREFIX)) return;
        if (event.getGuild() == null || event.getMember() == null) return;

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
            TeamformingService.SyncResult result = teamformingService.syncSelection(guild, member, section, event.getValues());

            event.reply(buildSyncMessage(section, result)).setEphemeral(true).queue();
        } catch (Exception e) {
            log.error("Failed to sync teamforming selection for section '{}', user {}",
                    sectionKey, event.getUser().getIdLong(), e);
            replyError(event);
        }
    }

    private String buildSyncMessage(TeamformingSection section, TeamformingService.SyncResult result) {
        if (result.added().isEmpty() && result.removed().isEmpty()) {
            return "Your " + section.title() + " tags are unchanged.";
        }

        StringBuilder sb = new StringBuilder();
        if (!result.added().isEmpty()) {
            sb.append("Added: **").append(String.join("**, **", result.added())).append("**");
        }
        if (!result.removed().isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("Removed: **").append(String.join("**, **", result.removed())).append("**");
        }
        return sb.toString();
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
