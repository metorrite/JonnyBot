package com.runeclans.bot.events;

import com.runeclans.bot.events.QueueRules.Entry;
import com.runeclans.bot.events.QueueRules.Policy;
import com.runeclans.bot.events.QueueRules.Quotas;
import com.runeclans.bot.events.TeamRules.Kind;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round robin and seat quotas for the fill queue (T-220, docs/EVENTS-CROSS-SERVER.md section 4). */
class FillQueueTest {
    private static final long HOST = 1, A = 2, B = 3;
    private static final Instant T0 = Instant.parse("2026-10-22T10:00:00Z");

    private static Entry e(long user, long origin, int minute) {
        return new Entry(user, origin == HOST ? Kind.HOME_CLAN : Kind.PARTNER, T0.plusSeconds(minute * 60L), origin);
    }

    private static List<Long> ids(List<Entry> entries) {
        return entries.stream().map(Entry::userId).toList();
    }

    @Test
    void roundRobinInterleavesServersHostFirstEachInJoinOrder() {
        // A's players waited longest, but the host still goes first; then A and B in the order their first player started waiting
        var waiting = List.of(e(10, A, 1), e(11, A, 2), e(12, A, 3), e(20, B, 4), e(30, HOST, 5), e(31, HOST, 6));
        assertEquals(List.of(30L, 10L, 20L, 31L, 11L, 12L), ids(QueueRules.order(Policy.ROUND_ROBIN, HOST, waiting)));
    }

    @Test
    void roundRobinSkipsAServerWithNobodyWaiting() {
        var waiting = List.of(e(10, A, 1), e(11, A, 2), e(20, B, 3));
        assertEquals(List.of(10L, 20L, 11L), ids(QueueRules.order(Policy.ROUND_ROBIN, HOST, waiting)));
        assertEquals(3, QueueRules.position(Policy.ROUND_ROBIN, HOST, waiting, 11L));
    }

    @Test
    void theOldPoliciesAreUnchanged() {
        var waiting = List.of(e(10, A, 1), e(30, HOST, 2), e(11, A, 3));
        assertEquals(List.of(10L, 30L, 11L), ids(QueueRules.order(Policy.FIRST_COME, HOST, waiting)));
        assertEquals(List.of(30L, 10L, 11L), ids(QueueRules.order(Policy.CLAN_FIRST, HOST, waiting)));
    }

    @Test
    void aPlayerOverTheirServersQuotaWaitsAndTheNextInLineWithRoomMovesIn() {
        Quotas quotas = new Quotas(Map.of(HOST, 2, A, 1), false);
        Map<Long, Integer> active = Map.of(HOST, 2, A, 0);
        assertFalse(quotas.hasRoom(HOST, active));
        assertTrue(quotas.hasRoom(A, active));
        assertTrue(quotas.hasRoom(B, active), "a server without a quota isn't limited");
        var waiting = List.of(e(30, HOST, 1), e(31, HOST, 2), e(10, A, 3));
        assertEquals(10L, QueueRules.next(Policy.FIRST_COME, HOST, waiting, quotas, active).userId());
        assertNull(QueueRules.next(Policy.FIRST_COME, HOST, List.of(e(30, HOST, 1)), quotas, active), "nobody with room: the place stays free until the release");
    }

    @Test
    void afterTheReleaseQuotasNoLongerLimitAnyone() {
        Quotas released = new Quotas(Map.of(HOST, 2), true);
        assertNull(released.limitFor(HOST));
        assertEquals(30L, QueueRules.next(Policy.FIRST_COME, HOST, List.of(e(30, HOST, 1)), released, Map.of(HOST, 5)).userId());
    }

    @Test
    void releaseIsTwentyFourHoursBeforeTheDeadlineOrWhenSignupsClose() {
        Instant closes = Instant.parse("2026-10-25T18:00:00Z");
        assertEquals(Instant.parse("2026-10-24T18:00:00Z"), QueueRules.releaseAt(closes, QueueRules.DEFAULT_RELEASE_HOURS));
        assertFalse(QueueRules.released(Instant.parse("2026-10-24T17:59:00Z"), closes, false, 24));
        assertTrue(QueueRules.released(Instant.parse("2026-10-24T18:00:00Z"), closes, false, 24));
        assertFalse(QueueRules.released(T0, null, false, 24), "no deadline: not released while signups are open");
        assertTrue(QueueRules.released(T0, null, true, 24), "released when signups close");
    }

    @Test
    void quotasNeedALimitAndMayNotAddUpToMoreThanIt() {
        assertNull(QueueRules.quotaProblem(0, null, List.of()), "no quotas is always fine");
        assertTrue(QueueRules.quotaProblem(0, 5, List.of()).contains("player limit"));
        assertNull(QueueRules.quotaProblem(20, 10, java.util.Arrays.asList(5, null, 5)));
        assertTrue(QueueRules.quotaProblem(20, 10, List.of(6, 5)).contains("add up to 21"));
    }

    @Test
    void theHostsQuotaSettingIsReadFromTheEventsSettings() {
        assertEquals(QueueRules.QuotaSetting.NONE, QueueRules.quotaSetting(DataObject.empty()));
        var setting = QueueRules.quotaSetting(DataObject.fromJson("{\"queueQuota\":{\"host\":6,\"releaseHours\":48}}"));
        assertEquals(6, setting.host());
        assertEquals(48, setting.releaseHours());
        assertNull(QueueRules.quotaSetting(DataObject.fromJson("{\"queueQuota\":{\"host\":0}}")).host(), "0 is no quota");
        assertFalse(QueueRules.quotaSettingProblems(DataObject.fromJson("{\"queueQuota\":{\"releaseHours\":1000}}")).isEmpty());
        assertTrue(EventSettings.problems(DataObject.fromJson("{\"queue\":\"ROUND_ROBIN\"}")).isEmpty());
    }
}
