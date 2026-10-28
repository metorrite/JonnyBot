package com.runeclans.bot.lockdown;

import net.dv8tion.jda.api.Permission;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The permission arithmetic of locking a channel, apart from Discord so it can be tested. Locking only changes one thing: it takes the "speak" permissions
 * away from @everyone in that channel and gives them explicitly to the roles that must keep talking. {@link Snapshot} remembers what each of those roles'
 * overwrites were before, so unlocking puts back exactly that (or removes the overwrite if there was none).
 */
public final class LockMath {
    private LockMath() {}

    /** What a locked channel's @everyone can no longer do. */
    public static final EnumSet<Permission> SPEAK = EnumSet.of(Permission.MESSAGE_SEND, Permission.MESSAGE_SEND_IN_THREADS, Permission.CREATE_PUBLIC_THREADS,
            Permission.CREATE_PRIVATE_THREADS, Permission.MESSAGE_ADD_REACTION);
    /** What the roles that keep talking are explicitly given, so the lock can't lock them out. */
    public static final EnumSet<Permission> KEEP_TALKING = EnumSet.of(Permission.MESSAGE_SEND, Permission.MESSAGE_SEND_IN_THREADS);

    public static final long SPEAK_RAW = Permission.getRaw(SPEAK);
    public static final long KEEP_TALKING_RAW = Permission.getRaw(KEEP_TALKING);

    /** One role's overwrite in a channel before it was locked. {@code existed} false means there was none. */
    public record Snapshot(long roleId, boolean existed, long allow, long deny) {}

    /** The overwrite to put on @everyone: the speak permissions denied (and no longer allowed), everything else as it was. */
    public static long[] lockedEveryone(Snapshot before) {
        return new long[]{before.allow() & ~SPEAK_RAW, before.deny() | SPEAK_RAW};
    }

    /** The overwrite to put on a role that keeps talking: the basic speak permissions allowed (and not denied). */
    public static long[] keepTalking(Snapshot before) {
        return new long[]{before.allow() | KEEP_TALKING_RAW, before.deny() & ~KEEP_TALKING_RAW};
    }

    /** "id:1:allow:deny;id:0:0:0" for storing a lock's snapshots. */
    public static String encode(List<Snapshot> snapshots) {
        StringBuilder out = new StringBuilder();
        for (Snapshot s : snapshots) {
            if (out.length() > 0) out.append(';');
            out.append(s.roleId()).append(':').append(s.existed() ? 1 : 0).append(':').append(s.allow()).append(':').append(s.deny());
        }
        return out.toString();
    }

    public static List<Snapshot> decode(String stored) {
        List<Snapshot> out = new ArrayList<>();
        if (stored == null || stored.isBlank()) return out;
        for (String part : stored.split(";")) {
            String[] f = part.split(":");
            if (f.length != 4) continue;
            try {
                out.add(new Snapshot(Long.parseLong(f[0]), f[1].equals("1"), Long.parseLong(f[2]), Long.parseLong(f[3])));
            } catch (NumberFormatException ignored) {
                // skip anything that isn't a snapshot
            }
        }
        return out;
    }
}
