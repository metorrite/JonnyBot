package com.younglings.bot.runescape.polling;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollQueueTest {
    private static final long NOW = 1_000_000;

    private final PollQueue queue = new PollQueue();

    private PollRequest request(String rsn, PollPriority priority) {
        return PollRequest.background(rsn, priority, Duration.ZERO, "test");
    }

    private List<String> drain(long now) {
        List<String> order = new ArrayList<>();
        queue.promoteDue(now);
        for (PollQueue.Item item = queue.peekReady(); item != null; item = queue.peekReady()) {
            order.add(item.rsn);
            queue.remove(item);
        }
        return order;
    }

    @Test
    void betterPrioritiesComeFirstAndEqualOnesInArrivalOrder() {
        queue.add(request("linked", PollPriority.LINKED), NOW);
        queue.add(request("clan one", PollPriority.CLAN), NOW);
        queue.add(request("premium", PollPriority.PREMIUM_CLAN), NOW);
        queue.add(request("clan two", PollPriority.CLAN), NOW);
        queue.add(request("button", PollPriority.INTERACTIVE), NOW);

        assertEquals(List.of("button", "premium", "clan one", "clan two", "linked"), drain(NOW));
    }

    @Test
    void anEntryThatIsNotDueYetNeverBlocksOneThatIs() {
        queue.add(request("later", PollPriority.INTERACTIVE).withDelay(Duration.ofSeconds(30)), NOW);
        queue.add(request("now", PollPriority.LINKED), NOW);

        assertEquals(List.of("now"), drain(NOW));
        assertEquals(NOW + 30_000, queue.nextWakeup().getAsLong());
        assertEquals(List.of("later"), drain(NOW + 30_000));
    }

    @Test
    void aPlayerIsHeldOnceWhateverTheCase() {
        PollQueue.Item first = queue.add(request("Jonny Young", PollPriority.CLAN), NOW);

        assertEquals(first, queue.get("jonny young"));
        assertEquals(first, queue.get("  JONNY YOUNG "));
        assertEquals(1, queue.size());
    }

    @Test
    void aSecondRequestTakesTheBetterPriorityTheStricterAgeAndTheEarlierTime() {
        PollQueue.Item item = queue.add(new PollRequest("a", PollPriority.LINKED, Duration.ofHours(4), Duration.ofMinutes(10), "tier"), NOW);

        boolean moved = queue.merge(item, PollRequest.interactive("a", "button"), NOW);

        assertTrue(moved);
        assertEquals(PollPriority.INTERACTIVE, item.priority);
        assertEquals(0, item.maxAgeMs);
        assertEquals(NOW, item.notBeforeMs);
        assertEquals("button", item.source);
        assertEquals(List.of("a"), drain(NOW));
    }

    @Test
    void aWorseRequestLeavesTheEntryAsItWas() {
        PollQueue.Item item = queue.add(request("a", PollPriority.CLAN), NOW);

        boolean moved = queue.merge(item, request("a", PollPriority.LINKED), NOW);

        assertFalse(moved);
        assertEquals(PollPriority.CLAN, item.priority);
    }

    @Test
    void aRemovedEntryCannotBeRemovedAgain() {
        PollQueue.Item item = queue.add(request("a", PollPriority.CLAN), NOW);

        assertTrue(queue.remove(item));
        assertFalse(queue.remove(item));
        assertNull(queue.get("a"));
    }

    @Test
    void aRequeuedEntryWaitsOutItsDelay() {
        PollQueue.Item item = queue.add(request("a", PollPriority.CLAN), NOW);
        queue.remove(item);

        queue.requeue(item, NOW + 60_000, NOW);

        assertEquals(List.of(), drain(NOW));
        assertEquals(List.of("a"), drain(NOW + 60_000));
    }

    @Test
    void countsAreKeptPerPriority() {
        queue.add(request("a", PollPriority.CLAN), NOW);
        queue.add(request("b", PollPriority.CLAN), NOW);
        queue.add(request("c", PollPriority.LINKED), NOW);

        var counts = queue.countsByPriority();

        assertEquals(2, counts.get(PollPriority.CLAN));
        assertEquals(1, counts.get(PollPriority.LINKED));
        assertEquals(0, counts.get(PollPriority.INTERACTIVE));
    }
}
