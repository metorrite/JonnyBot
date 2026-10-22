package com.younglings.bot.events;

import com.younglings.bot.events.EventQuickCreate.Kind;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventDefaultsTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final EventTemplate BINGO = new BingoTemplate();
    private static final EventTemplate COMPETITION = new CompetitionTemplate();

    private static EventDefaults set() {
        return new EventDefaults(Map.of("general", "{event} General Channel", "participantRole", "{event} Player"), BingoLooks.CLASSIC, "SIGNUP", "OFF");
    }

    @Test
    void aServerThatNeverSetAnythingGetsTheBuiltInStandardAnswers() {
        assertEquals(EventOptions.standard(BINGO, NOW).toString(), EventOptions.standard(BINGO, NOW, EventDefaults.NONE).toString());
        assertEquals(EventOptions.standard(BINGO, NOW).toString(), EventOptions.standard(BINGO, NOW, null).toString());
    }

    @Test
    void newEventsCopyWhatTheServerSet() {
        DataObject a = EventOptions.standard(BINGO, NOW, set());
        assertEquals("{event} General Channel", EventOptions.get(a, "config.channels.names.general"));
        assertEquals("{event} Player", EventOptions.get(a, "config.channels.names.participantRole"));
        assertEquals(BingoLooks.CLASSIC, EventOptions.get(a, "config.board.look"));
        assertEquals("SIGNUP", EventOptions.get(a, "config.verification.gate"));
        assertEquals("OFF", EventOptions.get(a, "config.setup.mode"));
        // the patterns land where the Channels tab reads them
        EventSpace.Setting s = EventSpace.parse(a.getObject("config"));
        assertEquals("{event} General Channel", s.pattern(EventSpace.Kind.GENERAL));
        assertEquals("{event} Player", s.pattern(EventSpace.RoleName.PARTICIPANT));
        assertEquals(EventSpace.Kind.STAFF.defaultPattern(), s.pattern(EventSpace.Kind.STAFF), "what the server didn't set stays built in");
    }

    @Test
    void theLookOnlyGoesOnBingos() {
        assertNull(EventOptions.get(EventOptions.standard(COMPETITION, NOW, set()), "config.board.look"));
    }

    @Test
    void quickCreateUsesTheServerLookButKeepsTheSetupStepsOff() {
        EventQuickCreate.Plan plan = EventQuickCreate.plan(Kind.BINGO, "Look night", 7, 24, null, NOW, null, new EventDefaults(Map.of(), BingoLooks.CLASSIC, null, "STANDARD"));
        DataObject config = plan.input().config();
        assertEquals(BingoLooks.CLASSIC, config.getObject("board").getString("look"));
        assertEquals("OFF", config.getObject("setup").getString("mode"));
        EventQuickCreate.Plan asked = EventQuickCreate.plan(Kind.BINGO, "Look night", 7, 24, null, NOW, BingoLooks.RUNESCAPE, new EventDefaults(Map.of(), BingoLooks.CLASSIC, null, null));
        assertEquals(BingoLooks.RUNESCAPE, asked.input().config().getObject("board").getString("look"), "a look asked for wins");
    }

    @Test
    void parsingKeepsOnlyRealSettings() {
        EventDefaults d = EventDefaults.parse(DataObject.fromJson("{\"names\":{\"general\":\"  {event} Chat  \",\"staff\":\"\"},\"look\":\"classic\",\"gate\":\"start\",\"setupMode\":\"off\"}"));
        assertEquals(Map.of("general", "{event} Chat"), d.names(), "trimmed, blanks mean built in");
        assertEquals("START", d.gate());
        assertEquals("OFF", d.setupMode());
        assertThrows(IllegalArgumentException.class, () -> EventDefaults.parse(DataObject.fromJson("{\"names\":{\"nope\":\"x\"}}")));
        assertThrows(IllegalArgumentException.class, () -> EventDefaults.parse(DataObject.fromJson("{\"look\":\"neon\"}")));
        assertThrows(IllegalArgumentException.class, () -> EventDefaults.parse(DataObject.fromJson("{\"gate\":\"never\"}")));
        assertThrows(IllegalArgumentException.class, () -> EventDefaults.parse(DataObject.fromJson("{\"setupMode\":\"FULL\"}")));
        assertThrows(IllegalArgumentException.class, () -> EventDefaults.parse(DataObject.fromJson("{\"names\":{\"general\":\"" + "x".repeat(101) + "\"}}")));
    }

    @Test
    void savedSettingsRoundTripAndBadOnesAreIgnored() {
        assertEquals(set(), EventDefaults.fromStored(set().json().toString()));
        assertTrue(EventDefaults.fromStored("{\"look\":\"neon\"}").isEmpty());
        assertTrue(EventDefaults.fromStored(null).isEmpty());
    }
}
