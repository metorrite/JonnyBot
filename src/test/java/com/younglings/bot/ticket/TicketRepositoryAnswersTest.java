package com.younglings.bot.ticket;

import com.younglings.bot.ticket.TicketModels.Answer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TicketRepositoryAnswersTest {
    @Test
    void answersSurviveTheDatabaseRoundTripIncludingAwkwardText() {
        List<Answer> answers = List.of(new Answer("Which?", "Amascut \"elites\"\nsecond line"), new Answer("Emoji 🎫", "100% — done, with 'quotes' and \\ backslash"));
        assertEquals(answers, TicketRepository.parseAnswers(TicketRepository.answersToJson(answers)));
    }

    @Test
    void emptyOrMissingAnswersParseAsNone() {
        assertEquals(List.of(), TicketRepository.parseAnswers(null));
        assertEquals(List.of(), TicketRepository.parseAnswers(""));
        assertEquals(List.of(), TicketRepository.parseAnswers("[]"));
    }

    @Test
    void columnListsGetTheirPrefix() {
        assertEquals("t.id, t.guild_id, t.number", TicketRepository.prefixed("t.", "id, guild_id,\n number"));
    }
}
