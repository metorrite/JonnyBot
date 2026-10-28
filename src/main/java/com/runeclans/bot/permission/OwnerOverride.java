package com.runeclans.bot.permission;

import com.runeclans.bot.notice.BotOwners;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The bot owner's "lift restrictions" mode for one server (T-445). By default the bot owner, like every RuneClan Admin (N5), may only work on a
 * server's clan site, its layout and its site roles. After an explicit acknowledgement on the website the owner may open the whole dashboard of
 * that one server as its Admin for the length the website sends (RuneClan Admin > Switches, {@code owner-override-minutes}), kept within
 * {@link #MIN_MINUTES} to {@link #MAX_MINUTES}, {@link #DEFAULT_MINUTES} when it sends none; {@link DashboardAccess#tierOf} answers ADMIN while it lasts. Only the bot's owner can
 * start it (checked by the caller, {@code TicketAdminApi}); every start, end and change is written to the server's own audit log, so the
 * clan sees it. Kept in memory on purpose: a bot restart ends every override, so nothing is left open by mistake. That also means it holds
 * only in the one bot process that took the lift: fine while the internal API runs in one process (it does today); with several API
 * replicas the lift would have to move to the database.
 * <p>
 * {@link #active} checks again that the person still owns the bot, from what is already known ({@link BotOwners#isKnownOwner}, no Discord call).
 */
@BService
public class OwnerOverride {
    /** How long one acknowledgement lasts when the website doesn't say, and the bounds it is kept within (the Switches panel's range). */
    public static final int DEFAULT_MINUTES = 30;
    public static final int MIN_MINUTES = 5;
    public static final int MAX_MINUTES = 240;

    private record Key(long guildId, long userId) {}

    private final Map<Key, Long> until = new ConcurrentHashMap<>();
    private final Map<Key, Integer> lengths = new ConcurrentHashMap<>();
    /** Unix milliseconds now; replaceable so tests can check expiry without waiting. */
    LongSupplier clock = System::currentTimeMillis;

    private final BotOwners owners;

    public OwnerOverride(BotOwners owners) {
        this.owners = owners;
    }

    /** {@code minutes} kept within the bounds; null (the website didn't say) is the default. */
    public static int minutes(Integer minutes) {
        if (minutes == null) return DEFAULT_MINUTES;
        return Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes));
    }

    /**
     * Starts (or renews) the override for this person in this server for {@code minutes} (kept within the bounds) and answers when it ends
     * (Unix ms). The caller has checked they own the bot.
     */
    public long lift(long guildId, long userId, Integer minutes) {
        int length = minutes(minutes);
        long end = clock.getAsLong() + length * 60_000L;
        Key key = new Key(guildId, userId);
        until.put(key, end);
        lengths.put(key, length);
        return end;
    }

    /** How long the running (or last) override here was lifted for, else the default. */
    public int length(long guildId, long userId) {
        return lengths.getOrDefault(new Key(guildId, userId), DEFAULT_MINUTES);
    }

    /** Ends the override now. True when one was running. */
    public boolean end(long guildId, long userId) {
        Long had = until.remove(new Key(guildId, userId));
        return had != null && had > clock.getAsLong();
    }

    /** When the running override ends (Unix ms), or 0 when there is none. */
    public long until(long guildId, long userId) {
        if (userId == 0) return 0;
        Key key = new Key(guildId, userId);
        Long end = until.get(key);
        if (end == null) return 0;
        if (end <= clock.getAsLong()) {
            until.remove(key, end);
            return 0;
        }
        return end;
    }

    public boolean active(long guildId, long userId) {
        return until(guildId, userId) != 0 && owners.isKnownOwner(userId);
    }
}
