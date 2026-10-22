package com.younglings.bot.rsnlink.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stand-ins for the verifier's ports: Jagex's public data as the test sets it, a clock the test moves, and a sink that records. No network. */
final class VerifyFakes {
    private VerifyFakes() {}

    /** Public data the test controls. Every call is counted; nothing leaves the machine. */
    static final class FakePublicData implements PublicData {
        final Map<Board, Hiscores> boards = new EnumMap<>(Board.class);
        RuneMetrics runeMetrics = new RuneMetrics.Private();
        final List<String> calls = new ArrayList<>();

        FakePublicData() {
            boards.put(Board.MAIN, new Hiscores.NotListed());
            boards.put(Board.IRONMAN, new Hiscores.NotListed());
        }

        @Override
        public Hiscores hiscores(Board board, String rsn) {
            calls.add(board.name() + ":" + rsn);
            return boards.get(board);
        }

        @Override
        public RuneMetrics runeMetrics(String rsn) {
            calls.add("RM:" + rsn);
            return runeMetrics;
        }

        /** Sets the main Hiscores to these whole-XP values (others unranked). */
        FakePublicData main(Map<Integer, Long> xp) {
            boards.put(Board.MAIN, new Hiscores.Found(table(xp)));
            return this;
        }

        FakePublicData ironman(Map<Integer, Long> xp) {
            boards.put(Board.IRONMAN, new Hiscores.Found(table(xp)));
            return this;
        }

        /** A public RuneMetrics profile with these values in tenths. */
        FakePublicData rm(Map<Integer, Long> xp10) {
            runeMetrics = new RuneMetrics.Public(table(xp10));
            return this;
        }

        long count(String prefix) {
            return calls.stream().filter(c -> c.startsWith(prefix)).count();
        }

        static long[] table(Map<Integer, Long> xp) {
            long[] t = new long[Skills.COUNT];
            Arrays.fill(t, Skills.UNRANKED);
            xp.forEach((k, v) -> t[k] = v);
            return t;
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    record Raised(UUID linkId, String evidence) {}

    static final class RecordingSink implements XpVerifier.Sink {
        final List<Raised> raised = new ArrayList<>();

        @Override
        public void corroborated(UUID linkId, String evidenceJson) {
            raised.add(new Raised(linkId, evidenceJson));
        }
    }

    static VerifySettings on() {
        return new VerifySettings(true, 2, 100, 24, 20, "test");
    }
}
