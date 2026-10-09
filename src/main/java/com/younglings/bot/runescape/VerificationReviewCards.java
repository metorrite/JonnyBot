package com.younglings.bot.runescape;

import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.EnumSet;
import java.util.List;

/**
 * The cards posted to the review channel for an RSN request. The bot remembers which message is each request's
 * card so that when the request ends some other way than a click on the card itself (the member cancels,
 * an admin approves from the Review Pending list, an admin links the name by hand), the card is edited to
 * say what happened and loses its Approve/Reject buttons, rather than staying live and answering "already
 * resolved" when someone clicks it.
 */
@BService
public class VerificationReviewCards {
    private static final Logger log = LoggerFactory.getLogger(VerificationReviewCards.class);

    private final PlayerLinkService linkService;

    public VerificationReviewCards(PlayerLinkService linkService) {
        this.linkService = linkService;
    }

    /** Remembers {@code message} as the card for this request. */
    public void track(long attemptId, Message message) {
        try {
            linkService.setReviewCard(attemptId, message.getChannelIdLong(), message.getIdLong());
        } catch (Exception e) {
            log.warn("Couldn't remember the review card for request {}; it won't be updated when the request ends", attemptId, e);
        }
    }

    /**
     * Edits this request's card, if one was recorded, to show {@code outcome} with no buttons. A card whose
     * message or channel has since been deleted is simply skipped.
     */
    public void update(Guild guild, long attemptId, Color accent, String outcome) {
        try {
            PlayerLinkRepository.ReviewCard card = linkService.getReviewCard(attemptId);
            VerificationAttempt attempt = linkService.getAttempt(attemptId);
            if (card == null || attempt == null) return;

            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, card.channelId());
            if (channel == null) return;

            channel.editMessageComponentsById(card.messageId(), List.of(resolvedCard(attempt, accent, outcome))).useComponentsV2(true)
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                    .queue(success -> {}, error -> log.debug("Couldn't update the review card for request {} (probably deleted): {}", attemptId, error.getMessage()));
        } catch (Exception e) {
            log.warn("Couldn't update the review card for request {}", attemptId, e);
        }
    }

    /** The card as it reads once the request is over: who asked for what, and how it ended. No buttons. */
    public static Container resolvedCard(VerificationAttempt attempt, Color accent, String outcome) {
        return Containers.card(accent,
                TextDisplay.of("### RSN Verification Request"),
                TextDisplay.of("<@" + attempt.discordUserId() + "> asked to link **" + attempt.rsn() + "**."),
                TextDisplay.of(outcome));
    }

    /** What a card says for a request that has ended: "Cancelled by the requester.", "✅ Approved by …", "❌ Rejected by …". */
    public static String outcome(String status, Long resolvedByUserId, long requesterId) {
        String by = resolvedByUserId == null ? "" : " by <@" + resolvedByUserId + ">";
        return switch (status) {
            case "CANCELLED" -> resolvedByUserId == null || resolvedByUserId == requesterId
                    ? "🚫 Cancelled by the requester." : "🚫 Cancelled" + by + ".";
            case "APPROVED" -> "✅ Approved" + by + ".";
            case "REJECTED" -> "❌ Rejected" + by + ".";
            default -> "This request has already been handled.";
        };
    }

    /** The accent that goes with {@link #outcome}. */
    public static Color accent(String status) {
        return switch (status) {
            case "APPROVED" -> Containers.SUCCESS;
            case "REJECTED" -> Containers.DANGER;
            default -> Containers.INFO;
        };
    }
}
