package com.younglings.bot.runescape;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Whether RuneMetrics is still asked at all ({@code RUNEMETRICS}). Jagex retires the RuneMetrics and Adventurer's Log APIs on {@link #SUNSET}.
 * {@code auto} (the default) asks until then and stops after, so nothing keeps calling a dead API; {@code on} keeps asking (if Jagex extends
 * it), {@code off} stops now. When off, profile polls answer {@link ProfileResult.Unavailable} without a request, as they would in an outage.
 */
public final class RuneMetricsSwitch {
    /** The announced shutdown, start of the day in UTC. */
    public static final Instant SUNSET = Instant.parse("2026-11-10T00:00:00Z");

    enum Mode { AUTO, ON, OFF }

    private RuneMetricsSwitch() {
    }

    /** From the {@code RUNEMETRICS} environment variable. */
    public static BooleanSupplier fromEnv() {
        return fromEnv(System::getenv, Clock.systemUTC());
    }

    /** @param env looks a variable up, null when unset */
    public static BooleanSupplier fromEnv(Function<String, String> env, Clock clock) {
        Mode mode = parse(env.apply("RUNEMETRICS"));
        return () -> on(mode, clock.instant());
    }

    static Mode parse(String raw) {
        if (raw == null) return Mode.AUTO;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true" -> Mode.ON;
            case "off", "false" -> Mode.OFF;
            default -> Mode.AUTO;
        };
    }

    static boolean on(Mode mode, Instant now) {
        return switch (mode) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> now.isBefore(SUNSET);
        };
    }
}
