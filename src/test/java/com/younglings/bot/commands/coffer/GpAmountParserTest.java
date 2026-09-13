package com.younglings.bot.commands.coffer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GpAmountParserTest {

    @ParameterizedTest
    @CsvSource({
            "150M,      150000000",
            "150m,      150000000",
            "500K,      500000",
            "500k,      500000",
            "1.5B,      1500000000",
            "1.5b,      1500000000",
            "150000,    150000",
            "150_000,   150000",
            "'  150K ', 150000", // surrounding whitespace is trimmed
    })
    void parsesShorthandAndPlainAmounts(String input, long expected) {
        assertEquals(expected, GpAmountParser.parse(input));
    }

    @Test
    void parsesAmountsWithThousandsSeparatorCommas() {
        // Not in the CSV source above since comma is the CsvSource column delimiter.
        assertEquals(150_000L, GpAmountParser.parse("150,000"));
        assertEquals(1_234_567L, GpAmountParser.parse("1,234,567"));
    }

    @Test
    void rejectsBlankOrNullInput() {
        assertThrows(IllegalArgumentException.class, () -> GpAmountParser.parse(null));
        assertThrows(IllegalArgumentException.class, () -> GpAmountParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> GpAmountParser.parse("   "));
    }

    @Test
    void rejectsNonNumericInput() {
        assertThrows(IllegalArgumentException.class, () -> GpAmountParser.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> GpAmountParser.parse("12X"));
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> GpAmountParser.parse("0"));
        assertThrows(IllegalArgumentException.class, () -> GpAmountParser.parse("-150"));
        assertThrows(IllegalArgumentException.class, () -> GpAmountParser.parse("-1M"));
    }

    @Test
    void toShorthandFormatsEachMagnitude() {
        assertEquals("999 GP", GpAmountParser.toShorthand(999));
        assertEquals("1K", GpAmountParser.toShorthand(1_000));
        assertEquals("1.5K", GpAmountParser.toShorthand(1_500));
        assertEquals("1M", GpAmountParser.toShorthand(1_000_000));
        assertEquals("2.5M", GpAmountParser.toShorthand(2_500_000));
        assertEquals("1B", GpAmountParser.toShorthand(1_000_000_000));
        assertEquals("1.5B", GpAmountParser.toShorthand(1_500_000_000));
    }

    @Test
    void formatIncludesBothPlainAndShorthand() {
        String formatted = GpAmountParser.format(1_234_567);
        assertEquals("1,234,567 GP (1.23M)", formatted);
    }

    @Test
    void parseAndToShorthandRoundTripForWholeMagnitudes() {
        assertEquals("150M", GpAmountParser.toShorthand(GpAmountParser.parse("150M")));
        assertEquals("500K", GpAmountParser.toShorthand(GpAmountParser.parse("500K")));
        assertEquals("2B", GpAmountParser.toShorthand(GpAmountParser.parse("2B")));
    }
}
