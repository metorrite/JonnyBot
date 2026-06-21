package com.younglings.bot.commands.coffer;

public class GpAmountParser {

    public static long parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Amount cannot be empty.");
        }

        String cleaned = input.trim().replace(",", "").replace("_", "");
        long multiplier = 1L;

        if (!cleaned.isEmpty()) {
            char last = cleaned.charAt(cleaned.length() - 1);
            if (last == 'k' || last == 'K') {
                multiplier = 1_000L;
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            } else if (last == 'm' || last == 'M') {
                multiplier = 1_000_000L;
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            } else if (last == 'b' || last == 'B') {
                multiplier = 1_000_000_000L;
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
        }

        double value;
        try {
            value = Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid amount: `" + input + "`. Examples: `150M`, `500K`, `1.5B`, `150000`, `150_000`");
        }

        if (value <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }

        return Math.round(value * multiplier);
    }

    public static String format(long amount) {
        return String.format("%,d", amount) + " GP (" + toShorthand(amount) + ")";
    }

    public static String toShorthand(long amount) {
        if (amount >= 1_000_000_000L) {
            return formatDecimal(amount / 1_000_000_000.0) + "B";
        } else if (amount >= 1_000_000L) {
            return formatDecimal(amount / 1_000_000.0) + "M";
        } else if (amount >= 1_000L) {
            return formatDecimal(amount / 1_000.0) + "K";
        } else {
            return amount + " GP";
        }
    }

    private static String formatDecimal(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.format("%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
