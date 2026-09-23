package com.younglings.bot.commands.poll;

import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

@BService
public class PollInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(PollInteractionListener.class);

    private final PollService pollService;

    public PollInteractionListener(PollService pollService) {
        this.pollService = pollService;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null) return;

        String id = event.getComponentId();
        if (!id.startsWith("poll_vote:")) return;

        try {
            handleVote(event, id);
        } catch (Exception e) {
            log.error("Unhandled exception in poll button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private void handleVote(ButtonInteractionEvent event, String id) {
        String[] parts = id.split(":");
        if (parts.length != 3) return;

        long pollId;
        int optionNumber;
        try {
            pollId = Long.parseLong(parts[1]);
            optionNumber = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return;
        }

        PollSession session = pollService.getSessionById(pollId);
        if (session == null || !"ACTIVE".equalsIgnoreCase(session.status())) {
            Containers.replyEphemeral(event, Containers.WARNING, "This poll is no longer active.");
            return;
        }

        List<PollOption> options = pollService.getOptions(pollId);
        PollOption option = options.stream()
                .filter(o -> o.optionNumber() == optionNumber)
                .findFirst()
                .orElse(null);

        if (option == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "This poll option no longer exists.");
            return;
        }

        PollService.VoteResult result = pollService.toggleVote(pollId, option.optionId(), event.getUser().getIdLong());

        if (result == PollService.VoteResult.POLL_CLOSED) {
            Containers.replyEphemeral(event, Containers.WARNING, "This poll is no longer active.");
            return;
        }

        String feedback = switch (result) {
            case ADDED    -> "✅  Your vote for **" + option.label() + "** has been recorded.";
            case REMOVED  -> "🗑️  Your vote for **" + option.label() + "** has been removed.";
            case SWITCHED -> "🔄  Switched your vote to **" + option.label() + "**.";
            default       -> "✅  Vote updated.";
        };

        pollService.updateMessage(event.getGuild(), pollId);

        Containers.replyThenDelete(event, Containers.SUCCESS, Duration.ofSeconds(4), feedback);
    }
}
