package com.younglings.bot.internal;

import com.younglings.bot.runescape.RuneMetricsSwitch;

import java.util.function.BooleanSupplier;

/**
 * Whether the Clan health page (T-593) and the rank-up advisor (T-594) can count Citadel caps at all (T-725). The stored cap weeks come only
 * from members' Adventurer's Logs, read through RuneMetrics, which Jagex retires on {@link RuneMetricsSwitch#SUNSET}. Once the bot stops
 * asking RuneMetrics ({@code RUNEMETRICS}: {@code auto} after the sunset, or {@code off}), no new cap weeks arrive, so the caps list would
 * quietly empty ("nobody missed caps") and a rank rule with a caps part could never be met. Instead both say "unavailable": the caps list
 * answers {@code available: false} with a reason, and the rank advisor leaves the caps part out of the count.
 * <p>
 * Rewiring: when caps come from the new Jagex API, change {@link #fromEnv} (and the readers of {@code SiteStatsRepository.citadelGrid}) here.
 * The plan for every RuneMetrics caller is runeclans-hub {@code docs/RUNEMETRICS-SUNSET.md}.
 */
final class CitadelCaps {
    private CitadelCaps() {}

    /** Shown on the Clan health page in place of the caps list (the website may show its own copy of it). */
    static final String UNAVAILABLE_REASON = "Citadel caps are unavailable: Jagex retired the Adventurer's Log on 10 November. "
            + "This list comes back once the new Jagex API is wired in.";

    /** Shown on the rank-up advisor's rules card: caps parts aren't counted, and the saved numbers wait for caps to return. */
    static final String RULES_REASON = "Citadel caps are unavailable since Jagex retired the Adventurer's Log, so caps parts aren't counted "
            + "for now. Ranks go on XP, days and points; your caps numbers stay saved for when caps return.";

    /** Caps are counted while the bot still asks RuneMetrics (see runeclans-hub docs/RUNEMETRICS-SUNSET.md). */
    static BooleanSupplier fromEnv() {
        return RuneMetricsSwitch.fromEnv();
    }
}
