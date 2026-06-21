package com.younglings.bot.commands.poll;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
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
            try {
                if (!event.isAcknowledged()) {
                    event.reply("An unexpected error occurred. Please try again.")
                            .setEphemeral(true).queue();
                }
            } catch (Exception ignored) {}
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
            event.reply("This poll is no longer active.").setEphemeral(true).queue();
            return;
        }

        List<PollOption> options = pollService.getOptions(pollId);
        PollOption option = options.stream()
                .filter(o -> o.optionNumber() == optionNumber)
                .findFirst()
                .orElse(null);

        if (option == null) {
            event.reply("This poll option no longer exists.").setEphemeral(true).queue();
            return;
        }

        PollService.VoteResult result = pollService.toggleVote(pollId, option.optionId(), event.getUser().getIdLong());

        if (result == PollService.VoteResult.POLL_CLOSED) {
            event.reply("This poll is no longer active.").setEphemeral(true).queue();
            return;
        }

        String feedback = switch (result) {
            case ADDED    -> "✅  Your vote for **" + option.label() + "** has been recorded.";
            case REMOVED  -> "🗑️  Your vote for **" + option.label() + "** has been removed.";
            case SWITCHED -> "🔄  Switched your vote to **" + option.label() + "**.";
            default       -> "✅  Vote updated.";
        };

        pollService.updateMessage(event.getGuild(), pollId);

        event.reply(feedback)
                .setEphemeral(true)
                .delay(Duration.ofSeconds(4))
                .flatMap(InteractionHook::deleteOriginal)
                .queue();
    }
}
