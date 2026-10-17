package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventScoring.Standing;
import com.younglings.bot.events.EventSubmit.Channel;
import com.younglings.bot.events.EventSubmit.Place;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure rules of {@code /submit}: which event and place a command belongs to, who may send from where, where the receipt goes, and which tiles are still open. */
class EventSubmitTest {
    private static ObjectiveSpec tile(String key, String label, Evidence evidence, int repeat, String... needs) {
        return new ObjectiveSpec(key, label, "", 10, evidence, 1, repeat, List.of(needs));
    }

    // ---------- the event and the place ----------

    @Test
    void anEventChannelNamesItsEventAndItsOwner() {
        EventSubmit.Route own = EventSubmit.route(new Channel(7, "player:500"), null, 500, null);
        assertTrue(own.ok());
        assertEquals(7L, own.eventId());
        assertEquals(Place.OWN_CHANNEL, own.place());
        assertEquals(Place.ANOTHER_PLAYERS, EventSubmit.route(new Channel(7, "player:501"), null, 500, null).place());
        assertEquals(Place.OWN_CHANNEL, EventSubmit.route(new Channel(7, "team:3"), null, 500, 3L).place());
        assertEquals(Place.ANOTHER_TEAMS, EventSubmit.route(new Channel(7, "team:4"), null, 500, 3L).place());
        assertEquals(Place.ANOTHER_TEAMS, EventSubmit.route(new Channel(7, "team:4"), null, 500, null).place());
        assertEquals(Place.EVENT_CHANNEL, EventSubmit.route(new Channel(7, "general"), null, 500, null).place());
    }

    @Test
    void anEventNumberTheyGaveBeatsTheChannelAndAChannelOfAnotherEventCountsAsElsewhere() {
        EventSubmit.Route route = EventSubmit.route(new Channel(7, "player:500"), 8L, 500, null);
        assertEquals(8L, route.eventId());
        assertEquals(Place.ELSEWHERE, route.place());
        assertEquals(Place.OWN_CHANNEL, EventSubmit.route(new Channel(7, "player:500"), 7L, 500, null).place());
        assertEquals(Place.ELSEWHERE, EventSubmit.route(null, 8L, 500, null).place());
    }

    @Test
    void outsideAnEventChannelWithNoNumberThereIsNoEvent() {
        EventSubmit.Route route = EventSubmit.route(null, null, 500, null);
        assertFalse(route.ok());
        assertNull(route.eventId());
        assertTrue(route.refusal().contains("event's number"), route.refusal());
    }

    @Test
    void aBrokenKeyIsNotAnyonesChannel() {
        assertEquals(Place.ANOTHER_PLAYERS, EventSubmit.placeOf(new Channel(7, "player:xyz"), 500, null));
        assertEquals(Place.ELSEWHERE, EventSubmit.placeOf(null, 500, null));
    }

    // ---------- who may send from where ----------

    @Test
    void aPlayerMaySendFromTheirOwnChannelAndTheEventsSharedOnesButNotSomeoneElses() {
        assertNull(EventSubmit.refusal(Place.OWN_CHANNEL, true, false));
        assertNull(EventSubmit.refusal(Place.EVENT_CHANNEL, true, false));
        assertNotNull(EventSubmit.refusal(Place.ANOTHER_PLAYERS, false, false));
        assertNotNull(EventSubmit.refusal(Place.ANOTHER_TEAMS, false, false));
    }

    @Test
    void sendingFromOutsideTheEventsChannelsIsRefusedOnlyWhenTheEventRequiresChannels() {
        assertNull(EventSubmit.refusal(Place.ELSEWHERE, false, false));
        String refused = EventSubmit.refusal(Place.ELSEWHERE, true, false);
        assertNotNull(refused);
        assertTrue(refused.contains("own channels"), refused);
    }

    @Test
    void staffAndOrganisersMaySendFromAnywhereEvenWhenChannelsAreRequired() {
        for (Place place : Place.values()) assertNull(EventSubmit.refusal(place, true, true), place.name());
    }

    // ---------- the receipt ----------

    @Test
    void theReceiptGoesToTheTeamsChannelInATeamEventAndThePlayersOwnOtherwise() {
        Set<String> keys = Set.of("general", "team:3", "player:500");
        assertEquals("team:3", EventSubmit.receiptKey(true, 3L, 500, keys));
        assertEquals("player:500", EventSubmit.receiptKey(false, 3L, 500, keys));
        assertEquals("player:500", EventSubmit.receiptKey(true, 4L, 500, keys));
        assertEquals("team:3", EventSubmit.receiptKey(false, 3L, 501, keys));
        assertNull(EventSubmit.receiptKey(true, null, 501, keys));
        assertNull(EventSubmit.receiptKey(false, null, 500, Set.of("general")));
    }

    // ---------- which tiles are open ----------

    @Test
    void onlyTilesThatTakeProofAndAreUnlockedAndNotFinishedAreOffered() {
        List<ObjectiveSpec> all = List.of(
                tile("a", "Dragon pickaxe", Evidence.PROOF, 1),
                tile("b", "Auto only", Evidence.AUTO, 1),
                tile("c", "Either way", Evidence.EITHER, 1),
                tile("d", "Needs a", Evidence.PROOF, 1, "a"),
                tile("e", "Repeatable", Evidence.PROOF, 3),
                tile("f", "By organiser", Evidence.ORGANISER, 1));
        assertEquals(List.of("a", "c", "e"), EventSubmit.openObjectives(all, null).stream().map(ObjectiveSpec::key).toList());

        Standing mine = new Standing("u:500", 20, 2, Map.of("a", 1, "e", 1), Instant.now());
        assertEquals(List.of("c", "d", "e"), EventSubmit.openObjectives(all, mine).stream().map(ObjectiveSpec::key).toList(),
                "a is done, so d unlocks; e can still be repeated");
        Standing finished = new Standing("u:500", 50, 3, Map.of("a", 1, "c", 1, "e", 3), Instant.now());
        assertEquals(List.of("d"), EventSubmit.openObjectives(all, finished).stream().map(ObjectiveSpec::key).toList());
    }

    @Test
    void aTypedTileIsFoundByItsLabelOrAnUnambiguousPart() {
        List<ObjectiveSpec> all = List.of(tile("a", "Dragon pickaxe", Evidence.PROOF, 1), tile("b", "Dragon axe", Evidence.PROOF, 1), tile("c", "Abyssal whip", Evidence.PROOF, 1));
        assertEquals("a", EventSubmit.byLabel(all, "dragon pickaxe").key());
        assertEquals("c", EventSubmit.byLabel(all, "whip").key());
        assertEquals("b", EventSubmit.byLabel(all, "b").key(), "an exact key works too");
        assertNull(EventSubmit.byLabel(all, "dragon"), "two tiles contain it");
        assertNull(EventSubmit.byLabel(all, "  "));
        assertNull(EventSubmit.byLabel(all, "rapier"));
    }

    // ---------- the settings and the receipt line ----------

    @Test
    void theSubmissionSettingsDefaultToReceiptsOnAndNotRequired() {
        assertEquals(new EventSettings.Submissions(false, true), EventSettings.parse(DataObject.fromJson("{}")).submissions());
        EventSettings.Submissions set = EventSettings.parse(DataObject.fromJson("{\"submissions\":{\"require\":true,\"receipts\":false}}")).submissions();
        assertTrue(set.require());
        assertFalse(set.receipts());
        assertTrue(EventSettings.problems(DataObject.fromJson("{\"submissions\":{\"require\":true}}")).isEmpty());
        assertFalse(EventSettings.problems(DataObject.fromJson("{\"submissions\":{\"require\":\"yes\"}}")).isEmpty());
    }

    @Test
    void theReceiptsLastLineSaysWhatStaffDecided() {
        assertEquals("✅ Approved", EventSubmissions.statusText(EventRepository.ClaimKind.OBJECTIVE, true, ""));
        assertEquals("✅ Verified", EventSubmissions.statusText(EventRepository.ClaimKind.VERIFICATION, true, ""));
        assertEquals("❌ Not accepted: too blurry", EventSubmissions.statusText(EventRepository.ClaimKind.OBJECTIVE, false, "too blurry"));
        assertEquals("❌ Not accepted", EventSubmissions.statusText(EventRepository.ClaimKind.VERIFICATION, false, " "));
    }
}
