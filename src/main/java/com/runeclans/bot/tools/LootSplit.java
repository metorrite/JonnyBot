package com.runeclans.bot.tools;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The arithmetic behind {@code /split}: reading amounts the way RuneScape players write them ("1.5b", "250m", "12,500k") and
 * dividing a drop between a group, optionally after a fee for the host or the clan. Whole coins only, and whatever does not
 * divide evenly is reported as a remainder instead of being lost.
 */
public final class LootSplit {
    /** Anything above this is surely a typo, and keeps every multiplication inside a long. */
    public static final long MAX_AMOUNT = 1_000_000_000_000L;
    public static final int MAX_PEOPLE = 1_000;

    private static final Pattern AMOUNT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([kmb]?)");

    private LootSplit() {}

    /** The result of a split: the fee taken first, then each person's share of what is left, and the coins that did not divide. */
    public record Result(long total, long fee, long pot, int people, long share, long remainder) {}

    /** Reads "250k", "1.5m", "2b", "1,250,000" or "1 250 000"; empty when it is not a positive amount within {@link #MAX_AMOUNT}. */
    public static Optional<Long> parseAmount(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String cleaned = text.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "").replace(" ", "");
        Matcher m = AMOUNT.matcher(cleaned);
        if (!m.matches()) {
            return Optional.empty();
        }
        BigDecimal value = new BigDecimal(m.group(1));
        value = switch (m.group(2)) {
            case "k" -> value.multiply(BigDecimal.valueOf(1_000L));
            case "m" -> value.multiply(BigDecimal.valueOf(1_000_000L));
            case "b" -> value.multiply(BigDecimal.valueOf(1_000_000_000L));
            default -> value;
        };
        if (value.compareTo(BigDecimal.ONE) < 0 || value.compareTo(BigDecimal.valueOf(MAX_AMOUNT)) > 0) {
            return Optional.empty();
        }
        return Optional.of(value.setScale(0, RoundingMode.DOWN).longValueExact());
    }

    /**
     * Splits {@code total} between {@code people} after taking {@code feePercent} (0 to 100, fractions allowed) off the top.
     * The fee rounds down, so the group never pays a coin more than the stated percentage.
     */
    public static Result split(long total, int people, double feePercent) {
        if (total < 0 || total > MAX_AMOUNT) {
            throw new IllegalArgumentException("total out of range");
        }
        if (people < 1 || people > MAX_PEOPLE) {
            throw new IllegalArgumentException("people out of range");
        }
        if (Double.isNaN(feePercent) || feePercent < 0 || feePercent > 100) {
            throw new IllegalArgumentException("fee out of range");
        }
        long fee = BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(feePercent)).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).longValueExact();
        long pot = total - fee;
        return new Result(total, fee, pot, people, pot / people, pot % people);
    }

    /** A coin amount for reading: {@code 1,250,000} with a short form beside large ones, e.g. {@code 1,250,000 (1.25m)}. */
    public static String format(long coins) {
        String full = String.format(Locale.ROOT, "%,d", coins);
        String shortForm = shorten(coins);
        return shortForm == null ? full : full + " (" + shortForm + ")";
    }

    private static String shorten(long coins) {
        if (coins >= 1_000_000_000L) {
            return trim(coins / 1_000_000_000.0) + "b";
        }
        if (coins >= 1_000_000L) {
            return trim(coins / 1_000_000.0) + "m";
        }
        if (coins >= 10_000L) {
            return trim(coins / 1_000.0) + "k";
        }
        return null;
    }

    private static String trim(double value) {
        String s = String.format(Locale.ROOT, "%.2f", value);
        s = s.indexOf('.') >= 0 ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
        return s;
    }
}
