package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventStandings.Visibility;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventStandingsTest {
    @Test
    void anEventThatNeverChoseShowsStandingsToEveryoneTakingPart() {
        assertEquals(Visibility.PARTICIPANTS, EventStandings.of(DataObject.empty()));
        assertEquals(Visibility.PARTICIPANTS, EventStandings.of(null));
        assertEquals(Visibility.PARTICIPANTS, EventStandings.of(DataObject.fromJson("{\"standings\":{\"visibility\":\"nonsense\"}}")));
        assertEquals(Visibility.HIDDEN_UNTIL_END, EventStandings.of(DataObject.fromJson("{\"standings\":{\"visibility\":\"hidden_until_end\"}}")));
    }

    @Test
    void theTableIsShownByChoiceAndStage() {
        for (Status s : Status.values()) {
            assertTrue(EventStandings.tableShown(Visibility.PARTICIPANTS, s));
            assertTrue(EventStandings.tableShown(Visibility.SERVER, s));
            assertFalse(EventStandings.tableShown(Visibility.STAFF, s));
        }
        assertFalse(EventStandings.tableShown(Visibility.HIDDEN_UNTIL_END, Status.RUNNING));
        assertTrue(EventStandings.tableShown(Visibility.HIDDEN_UNTIL_END, Status.SCORING), "the event has ended");
        assertTrue(EventStandings.tableShown(Visibility.HIDDEN_UNTIL_END, Status.DONE));
        assertTrue(EventStandings.hiddenText(Visibility.HIDDEN_UNTIL_END).contains("until the event ends"));
    }

    @Test
    void newEventsStartWithTheDefaultAndKeepAChoiceThroughCreation() {
        DataObject answers = EventOptions.standard(new BingoTemplate(), Instant.parse("2026-10-20T12:00:00Z"));
        assertEquals("PARTICIPANTS", EventOptions.get(answers, "config.standings.visibility"));
        EventOptions.set(answers, "config.standings.visibility", "STAFF");
        assertEquals(Visibility.STAFF, EventStandings.of(EventOptions.toInput(EventOptions.normalize(answers)).config()));
    }
}
