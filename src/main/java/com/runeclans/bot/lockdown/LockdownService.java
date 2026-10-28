package com.runeclans.bot.lockdown;

import com.runeclans.bot.lockdown.LockMath.Snapshot;
import com.runeclans.bot.lockdown.LockdownRepository.Config;
import com.runeclans.bot.lockdown.LockdownRepository.Lock;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Locking and unlocking channels. A lock only changes the overwrites of @everyone and of the roles chosen to keep talking, and first records exactly what
 * those were, so an unlock puts them back (or removes an overwrite that did not exist). A channel that is already locked is never locked again, so the
 * recorded originals can't be lost. Blocking: call from a thread that may wait on Discord.
 */
@BService
public class LockdownService {
    private static final Logger log = LoggerFactory.getLogger(LockdownService.class);

    /** What happened to one channel. */
    public enum Outcome { DONE, ALREADY, NOT_LOCKED, NO_PERMISSION, FAILED }

    private final LockdownRepository repository;

    public LockdownService(LockdownRepository repository) {
        this.repository = repository;
    }

    /** Whether JonnyBot can change who may talk in this channel. */
    public static boolean canManage(Guild guild, StandardGuildMessageChannel channel) {
        return guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MANAGE_PERMISSIONS, Permission.MESSAGE_SEND);
    }

    public Outcome lock(Guild guild, StandardGuildMessageChannel channel, long actorId, String reason, boolean lockdown) {
        if (repository.lock(guild.getIdLong(), channel.getIdLong()).isPresent()) return Outcome.ALREADY;
        if (!canManage(guild, channel)) return Outcome.NO_PERMISSION;

        Config config = repository.config(guild.getIdLong());
        List<Role> keepTalking = new ArrayList<>();
        for (long id : config.keepTalkingRoleIds()) {
            Role role = guild.getRoleById(id);
            if (role != null && !role.isPublicRole()) keepTalking.add(role);
        }

        // what the overwrites are now, written down before anything changes
        List<Snapshot> before = new ArrayList<>();
        before.add(snapshot(channel, guild.getPublicRole()));
        for (Role role : keepTalking) before.add(snapshot(channel, role));
        Lock lock = new Lock(guild.getIdLong(), channel.getIdLong(), actorId, Instant.now(), reason == null ? "" : reason, lockdown, LockMath.encode(before));
        if (!repository.addLock(lock)) return Outcome.ALREADY;

        try {
            long[] everyone = LockMath.lockedEveryone(before.get(0));
            channel.upsertPermissionOverride(guild.getPublicRole()).setPermissions(everyone[0], everyone[1]).reason(reasonText("Locked", actorId, reason)).complete();
            for (int i = 0; i < keepTalking.size(); i++) {
                long[] talk = LockMath.keepTalking(before.get(i + 1));
                channel.upsertPermissionOverride(keepTalking.get(i)).setPermissions(talk[0], talk[1]).reason(reasonText("Locked", actorId, reason)).complete();
            }
        } catch (RuntimeException e) {
            log.warn("Couldn't lock #{} in guild {}; putting it back", channel.getId(), guild.getId(), e);
            restore(guild, channel, before, "Lock failed");
            repository.removeLock(guild.getIdLong(), channel.getIdLong());
            return Outcome.FAILED;
        }
        notice(channel, "🔒 This channel is locked." + (reason == null || reason.isBlank() ? "" : " " + reason.strip()));
        return Outcome.DONE;
    }

    public Outcome unlock(Guild guild, StandardGuildMessageChannel channel, long actorId) {
        Lock lock = repository.lock(guild.getIdLong(), channel.getIdLong()).orElse(null);
        if (lock == null) return Outcome.NOT_LOCKED;
        if (!canManage(guild, channel)) return Outcome.NO_PERMISSION;
        try {
            restore(guild, channel, LockMath.decode(lock.snapshots()), reasonText("Unlocked", actorId, null));
        } catch (RuntimeException e) {
            log.warn("Couldn't unlock #{} in guild {}", channel.getId(), guild.getId(), e);
            return Outcome.FAILED;
        }
        repository.removeLock(guild.getIdLong(), channel.getIdLong());
        notice(channel, "🔓 This channel is unlocked.");
        return Outcome.DONE;
    }

    /** A saved lock whose channel has been deleted has nothing to restore: forget it. */
    public void forget(long guildId, long channelId) {
        repository.removeLock(guildId, channelId);
    }

    private static void restore(Guild guild, IPermissionContainer channel, List<Snapshot> before, String reason) {
        for (Snapshot snap : before) {
            Role role = guild.getRoleById(snap.roleId());
            if (role == null) continue;
            if (snap.existed()) {
                channel.upsertPermissionOverride(role).setPermissions(snap.allow(), snap.deny()).reason(reason).complete();
            } else {
                PermissionOverride now = channel.getPermissionOverride(role);
                if (now != null) now.delete().reason(reason).complete();
            }
        }
    }

    private static Snapshot snapshot(IPermissionContainer channel, Role role) {
        PermissionOverride existing = channel.getPermissionOverride(role);
        return existing == null ? new Snapshot(role.getIdLong(), false, 0, 0) : new Snapshot(role.getIdLong(), true, existing.getAllowedRaw(), existing.getDeniedRaw());
    }

    private static String reasonText(String what, long actorId, String reason) {
        String text = what + " by " + actorId + (reason == null || reason.isBlank() ? "" : ": " + reason.strip());
        return text.length() > 400 ? text.substring(0, 400) : text;
    }

    private static void notice(StandardGuildMessageChannel channel, String text) {
        if (channel.canTalk()) channel.sendMessage(text).setAllowedMentions(List.of()).queue(ok -> {}, error -> {});
    }

    // ---------- lockdown of the whole saved set ----------

    /** Locks every channel in the saved set that is not locked yet. Returns how many were locked, were already locked, and could not be locked. */
    public int[] start(Guild guild, long actorId, String reason) {
        int done = 0, already = 0, failed = 0;
        for (long id : repository.config(guild.getIdLong()).channelIds()) {
            StandardGuildMessageChannel channel = guild.getChannelById(StandardGuildMessageChannel.class, id);
            if (channel == null) {
                failed++;
                continue;
            }
            switch (lock(guild, channel, actorId, reason, true)) {
                case DONE -> done++;
                case ALREADY -> already++;
                default -> failed++;
            }
        }
        return new int[]{done, already, failed};
    }

    /** Unlocks the channels a lockdown locked (not ones someone locked by hand). Returns how many were unlocked and how many could not be. */
    public int[] end(Guild guild, long actorId) {
        int done = 0, failed = 0;
        for (Lock lock : repository.locks(guild.getIdLong())) {
            if (!lock.lockdown()) continue;
            StandardGuildMessageChannel channel = guild.getChannelById(StandardGuildMessageChannel.class, lock.channelId());
            if (channel == null) {
                forget(guild.getIdLong(), lock.channelId());
                continue;
            }
            if (unlock(guild, channel, actorId) == Outcome.DONE) done++; else failed++;
        }
        return new int[]{done, failed};
    }

    public Config config(long guildId) {
        return repository.config(guildId);
    }

    public void saveConfig(Config config) {
        repository.saveConfig(config);
    }

    public List<Lock> locks(long guildId) {
        return repository.locks(guildId);
    }
}
