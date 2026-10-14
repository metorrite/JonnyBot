package com.younglings.bot.wheel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelMessageTest {
    @Test
    void everyPlaceholderIsFilledIn() {
        String text = WheelMessage.render("{winner} won {entries} entries, {week}. Hi {mention}", "Axley", "<@5>", "7 Oct to 13 Oct", 12);
        assertEquals("**Axley** won 12 entries, 7 Oct to 13 Oct. Hi <@5>", text);
    }

    @Test
    void withoutALinkedAccountTheMentionShowsTheName() {
        assertEquals("Well done **Axley**!", WheelMessage.render("Well done {mention}!", "Axley", null, "", 3));
    }

    @Test
    void anEmptyTemplateFallsBackToTheDefault() {
        assertTrue(WheelMessage.render("  ", "Axley", null, "7 Oct to 13 Oct", 3).contains("Axley"));
    }

    @Test
    void theDefaultMessageIsValid() {
        assertTrue(WheelMessage.problems(WheelMessage.DEFAULT).isEmpty());
    }

    @Test
    void aMessageMustSayWhoWonAndStayShort() {
        assertFalse(WheelMessage.problems("Well done everyone").isEmpty(), "no {winner} or {mention}");
        assertFalse(WheelMessage.problems("").isEmpty());
        assertFalse(WheelMessage.problems(null).isEmpty());
        assertFalse(WheelMessage.problems("{winner}" + "x".repeat(WheelMessage.MAX_LENGTH)).isEmpty());
        assertTrue(WheelMessage.problems("{winner} won").isEmpty());
        assertTrue(WheelMessage.problems("{mention} won").isEmpty());
    }
}
