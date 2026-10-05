package com.younglings.bot.announcement;

import com.younglings.bot.commands.runescape.RsInteractionListener;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.tracking.WeeklyDigestService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Handles the buttons an Embedded Post can carry (see {@link PostMarkup}). Every button id looks like
 * {@code postbtn:<n>:<action>}; the action decides what a click does, and each action reuses the real
 * thing rather than copying it — {@code rs} is exactly what {@code /rs} does, {@code citadel} is the same
 * Citadel summary the {@code /rsadmin} Citadel viewer shows for this week — so they can't drift. Open to everyone: a button on a public post is
 * meant to be pressed by whoever reads it, and each action replies privately.
 * <p>
 * Adding an action: add its name to {@link PostMarkup#ACTIONS}, then a case below.
 */
@BService
public class PostActionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(PostActionListener.class);

    private final RsInteractionListener rsInteractionListener;
    private final WeeklyDigestService weeklyDigestService;

    public PostActionListener(RsInteractionListener rsInteractionListener, WeeklyDigestService weeklyDigestService) {
        this.rsInteractionListener = rsInteractionListener;
        this.weeklyDigestService = weeklyDigestService;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(PostMarkup.BUTTON_ID_PREFIX)) return;

        try {
            Guild guild = event.getGuild();
            if (guild == null) {
                Containers.replyEphemeral(event, Containers.WARNING, "This button only works inside the server.");
                return;
            }

            String action = id.split(":", 3)[2];
            switch (action) {
                case "rs" -> rsInteractionListener.openRs(event, guild, event.getUser().getIdLong());
                case "citadel" -> event.replyComponents(List.of(weeklyDigestService.buildWeekSummary(guild.getIdLong(), false)))
                        .useComponentsV2(true).setEphemeral(true).queue();
                default -> Containers.replyEphemeral(event, Containers.WARNING, "This button isn't connected to anything.");
            }
        } catch (Exception e) {
            log.error("Unhandled exception in post button '{}'", id, e);
            Containers.replyError(event);
        }
    }
}
