package com.younglings.bot.runescape;

import com.younglings.bot.discord.Containers;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.tree.ComponentTree;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a review card says once its request has ended, and that it has no buttons left to click. */
class VerificationReviewCardsTest {
    private static final long REQUESTER = 111L, ADMIN = 222L;

    @Test
    void aMemberCancellingTheirOwnRequestIsDescribedAsCancelledByTheRequester() {
        assertEquals("🚫 Cancelled by the requester.", VerificationReviewCards.outcome("CANCELLED", REQUESTER, REQUESTER));
        assertEquals("🚫 Cancelled by the requester.", VerificationReviewCards.outcome("CANCELLED", null, REQUESTER));
    }

    @Test
    void aCancelRecordedAgainstSomeoneElseNamesThem() {
        assertEquals("🚫 Cancelled by <@222>.", VerificationReviewCards.outcome("CANCELLED", ADMIN, REQUESTER));
    }

    @Test
    void approvedAndRejectedSayWhoDidIt() {
        assertEquals("✅ Approved by <@222>.", VerificationReviewCards.outcome("APPROVED", ADMIN, REQUESTER));
        assertEquals("❌ Rejected by <@222>.", VerificationReviewCards.outcome("REJECTED", ADMIN, REQUESTER));
        assertEquals("✅ Approved.", VerificationReviewCards.outcome("APPROVED", null, REQUESTER));
    }

    @Test
    void anUnknownStatusFallsBackToAPlainNotice() {
        assertEquals("This request has already been handled.", VerificationReviewCards.outcome("SOMETHING", ADMIN, REQUESTER));
    }

    @Test
    void theAccentFollowsTheOutcome() {
        assertEquals(Containers.SUCCESS, VerificationReviewCards.accent("APPROVED"));
        assertEquals(Containers.DANGER, VerificationReviewCards.accent("REJECTED"));
        assertEquals(Containers.INFO, VerificationReviewCards.accent("CANCELLED"));
    }

    @Test
    void anEndedCardKeepsWhoAskedForWhatShowsTheOutcomeAndHasNoButtons() {
        var attempt = new VerificationAttempt(25, 1, REQUESTER, "Master AGile", "Mohawk", "Brown", "Deep Dark", "CANCELLED");
        var card = VerificationReviewCards.resolvedCard(attempt, Containers.INFO, "🚫 Cancelled by the requester.");
        new MessageCreateBuilder().useComponentsV2(true).setComponents(card).build();

        var tree = ComponentTree.of(List.of(card));
        assertTrue(tree.findAll(Button.class).isEmpty(), "no Approve/Reject left on a finished request");
        String text = tree.findAll(TextDisplay.class).stream().map(TextDisplay::getContent).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(text.contains("<@111>") && text.contains("**Master AGile**") && text.contains("Cancelled by the requester"), text);
    }
}
