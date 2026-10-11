package com.younglings.bot.accountgate;

import java.time.Duration;
import java.time.Instant;

/**
 * Brand-new Discord accounts are where most raids and spam come from. When someone joins with an account younger than
 * {@code minAgeDays}, the server is told ({@link Action#ALERT}) or the account is also removed with a kick ({@link Action#KICK}), after
 * a DM saying why and when they can come back.
 */
public record AccountGateConfig(long guildId, boolean enabled, int minAgeDays, Action action, Long alertChannelId) {
    public static final int MAX_AGE_DAYS = 90;

    public enum Action { ALERT, KICK }

    public static AccountGateConfig defaults(long guildId) {
        return new AccountGateConfig(guildId, false, 7, Action.ALERT, null);
    }

    /** Whether an account made at {@code created} is too new to join at {@code now}. */
    public boolean tooNew(Instant created, Instant now) {
        return Duration.between(created, now).compareTo(Duration.ofDays(minAgeDays)) < 0;
    }

    /** When the account will be old enough, for the DM sent with a kick. */
    public Instant eligibleAt(Instant created) {
        return created.plus(Duration.ofDays(minAgeDays));
    }

    /** "3 days", "1 hour": how old the account is, for the alert. */
    public static String age(Instant created, Instant now) {
        long minutes = Math.max(0, Duration.between(created, now).toMinutes());
        if (minutes >= 2 * 24 * 60) return (minutes / (24 * 60)) + " days";
        if (minutes >= 24 * 60) return "1 day";
        if (minutes >= 120) return (minutes / 60) + " hours";
        if (minutes >= 60) return "1 hour";
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
