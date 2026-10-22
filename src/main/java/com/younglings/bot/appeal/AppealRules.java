package com.younglings.bot.appeal;

import com.younglings.bot.moderation.ModAction;
import com.younglings.bot.moderation.ModCase;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Ban and mute appeals (T-033): which moderation cases a person can appeal, and when. Pure, so it can be tested.
 * <ul>
 *   <li>A ban can be appealed while no later unban was recorded; a timeout while it is still running and no later "timeout removed" was recorded.
 *       (Discord is checked again when the appeal is sent, so a ban lifted by hand is caught there.)</li>
 *   <li>One open appeal per case. After a rejection the same case can be appealed again only once the server's cooldown has passed (7 days unless
 *       the server set another).</li>
 * </ul>
 */
public final class AppealRules {
    public static final int DEFAULT_COOLDOWN_DAYS = 7;
    public static final int MAX_COOLDOWN_DAYS = 365;
    public static final int MIN_TEXT = 20;
    public static final int MAX_TEXT = 2000;
    public static final int MAX_NOTE = 500;

    public enum Status { OPEN, APPROVED, REJECTED }

    /** A server's appeal settings: on or off, the ticket panel appeals open in, the cooldown after a rejection, and the optional copy channel. */
    public record Settings(long guildId, boolean enabled, Long panelId, int cooldownDays, Long mirrorChannelId) {
        public static Settings defaults(long guildId) {
            return new Settings(guildId, false, null, DEFAULT_COOLDOWN_DAYS, null);
        }
    }

    /** One appeal as stored. */
    public record Appeal(long id, long guildId, long caseNo, long userId, String userName, String text, Status status, Long ticketId, Long mirrorMessageId,
                         Instant createdAt, Long decidedBy, Instant decidedAt, String note) {}

    private AppealRules() {}

    /** The cases (newest first) the person can appeal now: their latest ban and their latest running timeout, if nothing lifted them since. */
    public static List<ModCase> appealable(List<ModCase> newestFirst, Instant now) {
        List<ModCase> out = new ArrayList<>();
        boolean banSeen = false, timeoutSeen = false, unbanned = false, untimed = false;
        for (ModCase c : newestFirst) {
            switch (c.action()) {
                case UNBAN -> unbanned = unbanned || !banSeen;
                case UNTIMEOUT -> untimed = untimed || !timeoutSeen;
                case BAN -> {
                    if (!banSeen && !unbanned) out.add(c);
                    banSeen = true;
                }
                case TIMEOUT -> {
                    boolean running = c.durationMinutes() != null && c.createdAt().plus(Duration.ofMinutes(c.durationMinutes())).isAfter(now);
                    if (!timeoutSeen && !untimed && running) out.add(c);
                    timeoutSeen = true;
                }
                default -> {
                }
            }
        }
        return out;
    }

    /** The action that lifts what a case did: an unban for a ban, removing the timeout for a timeout; null for anything else. */
    public static ModAction liftOf(ModAction action) {
        return switch (action) {
            case BAN -> ModAction.UNBAN;
            case TIMEOUT -> ModAction.UNTIMEOUT;
            default -> null;
        };
    }

    /**
     * Why this case can't be appealed now, or null when it can: there is an open appeal for it, or one was rejected less than the cooldown ago (the
     * answer says when they can try again). {@code earlier} are the person's appeals for this case.
     */
    public static String refusal(List<Appeal> earlier, int cooldownDays, Instant now) {
        Instant retry = null;
        for (Appeal a : earlier) {
            if (a.status() == Status.OPEN) return "You already have an appeal waiting for this case. Staff will look at it.";
            if (a.status() == Status.REJECTED && a.decidedAt() != null) {
                Instant until = a.decidedAt().plus(Duration.ofDays(Math.max(0, cooldownDays)));
                if (until.isAfter(now) && (retry == null || until.isAfter(retry))) retry = until;
            }
            if (a.status() == Status.APPROVED) return "Your appeal for this case was already approved.";
        }
        if (retry != null) return "Your last appeal for this case was turned down. You can appeal again from " + retry + ".";
        return null;
    }

    /** What is wrong with the appeal's text, or null. */
    public static String textProblem(String text) {
        String t = text == null ? "" : text.strip();
        if (t.length() < MIN_TEXT) return "Say a little more about why the case should be lifted (at least " + MIN_TEXT + " characters).";
        if (t.length() > MAX_TEXT) return "Keep the appeal to " + MAX_TEXT + " characters or fewer.";
        return null;
    }
}
