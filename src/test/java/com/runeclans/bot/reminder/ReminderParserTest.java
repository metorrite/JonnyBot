package com.runeclans.bot.reminder;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReminderParserTest {
    private static Optional<Duration> parse(String text) {
        return ReminderParser.parse(text);
    }

    @Test
    void shortSpellings() {
        assertEquals(Optional.of(Duration.ofMinutes(10)), parse("10m"));
        assertEquals(Optional.of(Duration.ofHours(2)), parse("2h"));
        assertEquals(Optional.of(Duration.ofDays(1)), parse("1d"));
        assertEquals(Optional.of(Duration.ofDays(14)), parse("2w"));
    }

    @Test
    void compoundLengthsAddUp() {
        assertEquals(Optional.of(Duration.ofMinutes(150)), parse("2h30m"));
        assertEquals(Optional.of(Duration.ofMinutes(150)), parse("2h 30m"));
        assertEquals(Optional.of(Duration.ofHours(27)), parse("1d3h"));
    }

    @Test
    void wordsAndFillerAreAccepted() {
        assertEquals(Optional.of(Duration.ofMinutes(90)), parse("90 minutes"));
        assertEquals(Optional.of(Duration.ofHours(3).plusMinutes(20)), parse("in 3 hours and 20 minutes"));
        assertEquals(Optional.of(Duration.ofDays(3)), parse("3 days"));
        assertEquals(Optional.of(Duration.ofMinutes(5)), parse("  5 Mins "));
    }

    @Test
    void thingsItCannotReadAreRejected() {
        assertEquals(Optional.empty(), parse(""));
        assertEquals(Optional.empty(), parse("soon"));
        assertEquals(Optional.empty(), parse("10"));
        assertEquals(Optional.empty(), parse("10m banana 5m"));
        assertEquals(Optional.empty(), parse("5 parsecs"));
        assertEquals(Optional.empty(), parse(null));
    }

    @Test
    void tooShortOrTooLongIsRejected() {
        assertEquals(Optional.empty(), parse("30s"));
        assertEquals(Optional.empty(), parse("2 years"));
        assertEquals(Optional.empty(), parse("400d"));
        assertEquals(Optional.of(Duration.ofDays(365)), parse("365d"));
    }
}
