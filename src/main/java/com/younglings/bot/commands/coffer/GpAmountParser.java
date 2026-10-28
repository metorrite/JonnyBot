package com.younglings.bot.commands.coffer;

import java.util.Locale;
import java.util.regex.Pattern;

public class GpAmountParser {

    /** Plain digits with an optional decimal part. Double.parseDouble alone would also take "NaN", "Infinity", "1e9" and "5d". */
    private static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?|\\.\\d+");
    /** No real coffer holds more; it also keeps the value far from Long overflow. */
    private static final long MAX_AMOUNT = 1_000_000_000_000_000L;

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

        cleaned = cleaned.strip();
        if (cleaned.startsWith("-")) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
        if (!NUMBER.matcher(cleaned).matches()) {
            throw new IllegalArgumentException(
                    "Invalid amount: `" + input + "`. Examples: `150M`, `500K`, `1.5B`, `150000`, `150_000`");
        }

        double value = Double.parseDouble(cleaned) * multiplier;
        if (value > MAX_AMOUNT) {
            throw new IllegalArgumentException("That amount is too large.");
        }
        long amount = Math.round(value);
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
        return amount;
    }

    public static String format(long amount) {
        return String.format("%,d", amount) + " GP (" + toShorthand(amount) + ")";
    }

    public static String toShorthand(long amount) {
        // 999,999 rounds to "1000K": say it in the next unit up instead
        if (amount >= 1_000_000_000L || round2(amount / 1_000_000.0) >= 1_000) {
            return formatDecimal(amount / 1_000_000_000.0) + "B";
        } else if (amount >= 1_000_000L || round2(amount / 1_000.0) >= 1_000) {
            return formatDecimal(amount / 1_000_000.0) + "M";
        } else if (amount >= 1_000L) {
            return formatDecimal(amount / 1_000.0) + "K";
        } else {
            return amount + " GP";
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String formatDecimal(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
