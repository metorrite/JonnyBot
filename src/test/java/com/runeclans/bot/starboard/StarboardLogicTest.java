package com.runeclans.bot.starboard;

import com.runeclans.bot.starboard.StarboardLogic.Action;
import com.runeclans.bot.starboard.StarboardLogic.Reactor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StarboardLogicTest {
    private static final long AUTHOR = 7;

    @Test
    void everyRealPersonCounts() {
        assertEquals(3, StarboardLogic.count(List.of(new Reactor(1, false), new Reactor(2, false), new Reactor(3, false)), AUTHOR, false));
    }

    @Test
    void botsNeverCount() {
        assertEquals(1, StarboardLogic.count(List.of(new Reactor(1, false), new Reactor(2, true)), AUTHOR, true));
    }

    @Test
    void theAuthorsOwnStarCountsOnlyIfTheServerAllowsIt() {
        List<Reactor> reactors = List.of(new Reactor(AUTHOR, false), new Reactor(1, false));

        assertEquals(1, StarboardLogic.count(reactors, AUTHOR, false));
        assertEquals(2, StarboardLogic.count(reactors, AUTHOR, true));
    }

    @Test
    void reachingTheThresholdPostsOnceThenUpdates() {
        assertEquals(Action.NOTHING, StarboardLogic.decide(2, 3, false));
        assertEquals(Action.POST, StarboardLogic.decide(3, 3, false));
        assertEquals(Action.UPDATE, StarboardLogic.decide(5, 3, true));
    }

    @Test
    void fallingBelowTheThresholdTakesItDown() {
        assertEquals(Action.REMOVE, StarboardLogic.decide(2, 3, true));
        assertEquals(Action.NOTHING, StarboardLogic.decide(0, 3, false));
    }

    @Test
    void theHeadlineShowsTheCountAndWhereItCameFrom() {
        assertEquals("⭐ **4** | <#55>", StarboardLogic.headline("⭐", 4, 55));
    }
}
