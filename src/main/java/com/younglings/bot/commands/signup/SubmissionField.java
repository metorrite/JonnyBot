package com.younglings.bot.commands.signup;

import java.util.ArrayList;
import java.util.List;

public record SubmissionField(String label, String type, boolean required) {

    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_LINK = "LINK";
    public static final String TYPE_IMAGE = "IMAGE";

    /** Normalizes a user-supplied type string to one of the known constants. */
    public static String normalizeType(String raw) {
        if (raw == null) return TYPE_TEXT;
        return switch (raw.trim().toUpperCase()) {
            case "IMAGE" -> TYPE_IMAGE;
            case "LINK"  -> TYPE_LINK;
            default      -> TYPE_TEXT;
        };
    }

    /** Serialize a list of fields to a DB-storable string. Format: label:type:R|label:type:O */
    public static String serialize(List<SubmissionField> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append("|");
            // Strip separator chars from the label to prevent parse collisions
            sb.append(fields.get(i).label().replace("|", "").replace(":", ""))
              .append(":").append(fields.get(i).type())
              .append(":").append(fields.get(i).required() ? "R" : "O");
        }
        return sb.toString();
    }

    /** Restore a list of fields from a serialized DB string. Backward-compatible: missing required segment defaults to required. */
    public static List<SubmissionField> deserialize(String s) {
        List<SubmissionField> result = new ArrayList<>();
        if (s == null || s.isBlank()) return result;
        for (String part : s.split("\\|")) {
            String[] segments = part.split(":");
            if (segments.length < 1 || segments[0].isBlank()) continue;
            String label    = segments[0];
            String type     = segments.length >= 2 ? segments[1] : TYPE_TEXT;
            boolean required = segments.length < 3 || !"O".equals(segments[2]);
            result.add(new SubmissionField(label, type, required));
        }
        return result;
    }

    /** Serialize user-submitted values to a DB-storable string. */
    public static String serializeValues(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(values.get(i).replace("|", ""));
        }
        return sb.toString();
    }

    /** Parse user-submitted values from a serialized DB string. */
    public static List<String> parseValues(String s) {
        if (s == null || s.isBlank()) return List.of();
        return List.of(s.split("\\|", -1));
    }
}
