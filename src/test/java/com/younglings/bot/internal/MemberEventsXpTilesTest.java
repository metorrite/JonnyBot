package com.younglings.bot.internal;

import com.younglings.bot.events.BingoTemplate;
import com.younglings.bot.events.CustomTemplate;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.PluginObjectiveMatcher;
import com.younglings.bot.events.PluginXpTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The member event page's XP tiles (T-707): the same labels as the hub event page while XP tiles are counted, and whether the player isn't counted. */
class MemberEventsXpTilesTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long PLAYER = Long.parseLong("5".repeat(18));
    private static final List<ObjectiveSpec> TILES = List.of(new ObjectiveSpec("t1", "Gain 1M Fletching XP", "", 1, Evidence.EITHER, 1, 1, List.of()),
            new ObjectiveSpec("t2", "Nex: Torva boots", "", 1, Evidence.EITHER, 1, 1, List.of()));

    @AfterEach
    void offAgain() {
        PluginObjectiveMatcher.offerXpTiles(false);
    }

    private static Event event(String template) {
        Instant at = Instant.parse("2026-10-20T18:00:00Z");
        return new Event(1, GUILD, template, "Bingo", "", Status.RUNNING, null, ScoringMode.INDIVIDUAL, at, at.plusSeconds(86_400), 0, "{}", 1, at, null, false);
    }

    @Test
    void theXpTilesAreListedOnlyWhileTheyAreCounted() {
        assertNull(MemberEventsApi.xpTiles(event(BingoTemplate.KEY), TILES), "off (the default): nothing changes");
        PluginObjectiveMatcher.offerXpTiles(true);
        assertEquals("[\"Gain 1M Fletching XP\"]", MemberEventsApi.xpTiles(event(BingoTemplate.KEY), TILES).toString());
        assertNull(MemberEventsApi.xpTiles(event(CustomTemplate.KEY), TILES), "only bingo and scavenger hunts count XP tiles");
    }

    @Test
    void aPlayerTheLastPassDidntCountIsToldItAppliesToThem() {
        PluginXpTracker tracker = mock(PluginXpTracker.class);
        when(tracker.notCounted(1)).thenReturn(Optional.of(new PluginXpTracker.NotCounted(Set.of(PLAYER), Set.of(), Instant.EPOCH)));
        assertTrue(MemberEventsApi.notCounted(tracker, 1, PLAYER));
        assertFalse(MemberEventsApi.notCounted(tracker, 1, PLAYER + 1), "someone else");
        assertFalse(MemberEventsApi.notCounted(tracker, 2, PLAYER), "before the first pass");
        assertFalse(MemberEventsApi.notCounted(null, 1, PLAYER), "no tracker");
    }
}
