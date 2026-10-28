package com.runeclans.bot.hubsites;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Who holds the roles a site's rules follow, reported to the hub (T-505, D-13): only roles the hub's {@code role-mappings} name, one
 * {@code PUT /v1/guilds/{guildId}/role-members/{roleId}} per role with every holder. Member role changes are gathered for a few seconds
 * so a burst (a role panel, a bulk edit) still sends one report per role. Discord ids go only to the hub, never to the log.
 */
final class RoleReports {
    /** How long role changes are gathered before the report goes out. */
    static final Duration DEBOUNCE = Duration.ofSeconds(5);

    /** Runs {@code task} once after {@code delay}. */
    interface Timer {
        void after(Duration delay, Runnable task);
    }

    /** Sends one role's holders to the hub. */
    interface Sender {
        void send(long guildId, long roleId, List<Long> holders);
    }

    record Key(long guildId, long roleId) {}

    private final Function<Long, Optional<Set<Long>>> mappedRoles;
    /** Every holder of a role in a server, or null when Discord couldn't say (nothing is sent then: never a wrong "nobody"). */
    private final BiFunction<Long, Long, List<Long>> holders;
    private final Sender sender;
    private final Timer timer;
    private final Set<Key> pending = new LinkedHashSet<>();
    private boolean scheduled;

    RoleReports(Function<Long, Optional<Set<Long>>> mappedRoles, BiFunction<Long, Long, List<Long>> holders, Sender sender, Timer timer) {
        this.mappedRoles = mappedRoles;
        this.holders = holders;
        this.sender = sender;
        this.timer = timer;
    }

    /** A member's roles changed in {@code guildId}: the mapped ones among {@code roleIds} are reported after {@link #DEBOUNCE}. */
    void changed(long guildId, Collection<Long> roleIds) {
        Set<Long> mapped = mappedRoles.apply(guildId).orElse(Set.of());
        boolean schedule = false;
        synchronized (this) {
            for (long role : roleIds) if (mapped.contains(role)) pending.add(new Key(guildId, role));
            if (!pending.isEmpty() && !scheduled) {
                scheduled = true;
                schedule = true;
            }
        }
        if (schedule) timer.after(DEBOUNCE, this::flush);
    }

    /** Reports every mapped role of {@code guildId} now (on start). Returns how many roles were reported. */
    int all(long guildId) {
        int sent = 0;
        for (long role : mappedRoles.apply(guildId).orElse(Set.of())) if (report(new Key(guildId, role))) sent++;
        return sent;
    }

    /** Sends the gathered roles, one report each. Returns how many were sent. */
    int flush() {
        List<Key> due;
        synchronized (this) {
            due = List.copyOf(pending);
            pending.clear();
            scheduled = false;
        }
        int sent = 0;
        for (Key key : due) if (report(key)) sent++;
        return sent;
    }

    private boolean report(Key key) {
        List<Long> who = holders.apply(key.guildId(), key.roleId());
        if (who == null) return false;
        sender.send(key.guildId(), key.roleId(), who);
        return true;
    }
}
