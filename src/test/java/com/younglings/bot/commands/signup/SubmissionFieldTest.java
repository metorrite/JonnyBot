package com.younglings.bot.commands.signup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionFieldTest {

    // --- normalizeType ---

    @Test
    void normalizeTypeRecognizesKnownTypesCaseInsensitively() {
        assertEquals(SubmissionField.TYPE_IMAGE, SubmissionField.normalizeType("image"));
        assertEquals(SubmissionField.TYPE_IMAGE, SubmissionField.normalizeType("IMAGE"));
        assertEquals(SubmissionField.TYPE_LINK, SubmissionField.normalizeType("Link"));
        assertEquals(SubmissionField.TYPE_TEXT, SubmissionField.normalizeType("text"));
    }

    @Test
    void normalizeTypeFallsBackToTextForNullOrUnknown() {
        assertEquals(SubmissionField.TYPE_TEXT, SubmissionField.normalizeType(null));
        assertEquals(SubmissionField.TYPE_TEXT, SubmissionField.normalizeType("not-a-real-type"));
        assertEquals(SubmissionField.TYPE_TEXT, SubmissionField.normalizeType(""));
    }

    // --- serialize / deserialize (field definitions) ---

    @Test
    void serializeThenDeserializeRoundTripsFieldDefinitions() {
        List<SubmissionField> original = List.of(
                new SubmissionField("Movie", SubmissionField.TYPE_TEXT, true),
                new SubmissionField("Poster", SubmissionField.TYPE_IMAGE, false),
                new SubmissionField("Trailer", SubmissionField.TYPE_LINK, true)
        );

        String serialized = SubmissionField.serialize(original);
        List<SubmissionField> restored = SubmissionField.deserialize(serialized);

        assertEquals(original, restored);
    }

    @Test
    void serializeStripsSeparatorCharsFromLabelsToPreventParseCollisions() {
        SubmissionField field = new SubmissionField("Weird|Label:Name", SubmissionField.TYPE_TEXT, true);
        String serialized = SubmissionField.serialize(List.of(field));

        List<SubmissionField> restored = SubmissionField.deserialize(serialized);

        assertEquals(1, restored.size());
        assertEquals("WeirdLabelName", restored.get(0).label());
    }

    @Test
    void deserializeIsBackwardCompatibleWithMissingSegments() {
        // Old-format string with only a label and no type/required segments.
        List<SubmissionField> restored = SubmissionField.deserialize("Movie");

        assertEquals(1, restored.size());
        assertEquals("Movie", restored.get(0).label());
        assertEquals(SubmissionField.TYPE_TEXT, restored.get(0).type());
        assertTrue(restored.get(0).required(), "missing required segment should default to required=true");
    }

    @Test
    void deserializeOfBlankOrNullReturnsEmptyList() {
        assertEquals(List.of(), SubmissionField.deserialize(null));
        assertEquals(List.of(), SubmissionField.deserialize(""));
        assertEquals(List.of(), SubmissionField.deserialize("   "));
    }

    // --- serializeValues / parseValues (submitted values) ---

    @Test
    void serializeValuesThenParseValuesRoundTrips() {
        List<String> original = List.of("Inception", "https://example.com/poster.png", "");

        String serialized = SubmissionField.serializeValues(original);
        List<String> restored = SubmissionField.parseValues(serialized);

        assertEquals(original, restored);
    }

    @Test
    void serializeValuesStripsPipeCharsFromValues() {
        String serialized = SubmissionField.serializeValues(List.of("a|b", "c"));
        assertEquals(List.of("ab", "c"), SubmissionField.parseValues(serialized));
    }

    @Test
    void parseValuesOfBlankOrNullReturnsEmptyList() {
        assertEquals(List.of(), SubmissionField.parseValues(null));
        assertEquals(List.of(), SubmissionField.parseValues(""));
    }
}
