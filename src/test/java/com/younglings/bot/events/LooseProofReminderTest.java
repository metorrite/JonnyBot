package com.younglings.bot.events;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LooseProofReminderTest {
    @Test
    void theReminderIsOffUnlessTheEventAsksForIt() {
        assertFalse(EventSettings.parse(DataObject.fromJson("{}")).submissions().remind());
        assertFalse(EventSettings.parse(DataObject.fromJson("{\"submissions\":{\"require\":true}}")).submissions().remind());
        assertTrue(EventSettings.parse(DataObject.fromJson("{\"submissions\":{\"remind\":true}}")).submissions().remind());
        assertEquals(1, EventSettings.problems(DataObject.fromJson("{\"submissions\":{\"remind\":\"yes\"}}")).size());
    }

    @Test
    void onlyAPlayersOrTeamsChannelCounts() {
        assertTrue(LooseProofReminderListener.isProofChannel("player:123"));
        assertTrue(LooseProofReminderListener.isProofChannel("team:7"));
        assertFalse(LooseProofReminderListener.isProofChannel("general"));
        assertFalse(LooseProofReminderListener.isProofChannel(null));
    }

    @Test
    void oneReminderPerPlayerPerHourAndNeverForOrganisers() {
        LooseProofReminderListener listener = new LooseProofReminderListener(null, null, null);
        assertFalse(listener.due(1, 10, true, 0), "organisers and staff are never reminded");
        assertTrue(listener.due(1, 10, false, 1_000));
        assertFalse(listener.due(1, 10, false, 1_000 + LooseProofReminderListener.EVERY_MILLIS - 1), "still inside the hour");
        assertTrue(listener.due(1, 10, false, 1_000 + LooseProofReminderListener.EVERY_MILLIS), "an hour later");
        assertTrue(listener.due(2, 10, false, 2_000), "another event is separate");
        assertTrue(listener.due(1, 11, false, 2_000), "another player is separate");
    }
}
