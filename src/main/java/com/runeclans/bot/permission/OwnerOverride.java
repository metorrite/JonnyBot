package com.runeclans.bot.permission;

import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The bot owner's "lift restrictions" mode for one server (T-445). By default the bot owner, like every RuneClan Admin (N5), may only work on a
 * server's clan site, its layout and its site roles. After an explicit acknowledgement on the website the owner may open the whole dashboard of
 * that one server as its Admin for {@link #DURATION_MS}; {@link DashboardAccess#tierOf} answers ADMIN while it lasts. Only the bot's owner can
 * start it (checked by the caller, {@code TicketAdminApi}); every start, end and change is written to the server's own audit log, so the
 * clan sees it. Kept in memory on purpose: a bot restart ends every override, so nothing is left open by mistake.
 */
@BService
public class OwnerOverride {
    /** How long one acknowledgement lasts. */
    public static final long DURATION_MS = 30 * 60_000L;

    private record Key(long guildId, long userId) {}

    private final Map<Key, Long> until = new ConcurrentHashMap<>();
    /** Unix milliseconds now; replaceable so tests can check expiry without waiting. */
    LongSupplier clock = System::currentTimeMillis;

    public OwnerOverride() {}

    /** Starts (or renews) the override for this person in this server and answers when it ends (Unix ms). The caller has checked they own the bot. */
    public long lift(long guildId, long userId) {
        long end = clock.getAsLong() + DURATION_MS;
        until.put(new Key(guildId, userId), end);
        return end;
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
        return until(guildId, userId) != 0;
    }
}
