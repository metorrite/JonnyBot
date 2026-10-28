package com.runeclans.bot.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.sharding.ShardManager;

/**
 * Which servers this process is responsible for, and when a missing server really means the bot left it (T-700, docs SCALING-STATE M9).
 *
 * <p>A process only sees the servers on the shards it runs. Once the bot runs as several processes (or an {@code api} process with no
 * gateway), {@code Shards.guild(...) == null} can mean "on someone else's shard" or "not loaded yet" as well as "the bot was removed". Work
 * that throws data away when a server is missing (clearing a poll's end time, dropping a delayed autorole, losing a stream alert, deleting a
 * server's data) asks {@link #gone} instead, and work that loops over database rows can skip the rows of servers it doesn't {@link #owns own}.
 *
 * <p>With one process running every shard (today), {@link #owns} is always true and {@link #gone} is "missing, and its shard is connected
 * and not reporting a Discord outage". A stand-alone {@code JDA} (tests) answers exactly as {@code jda.getGuildById(id) == null} did.
 */
public final class ShardRouting {
    private ShardRouting() {
    }

    /** Whether this process runs the shard that holds this server (always true for a stand-alone {@code JDA}). */
    public static boolean owns(JDA jda, long guildId) {
        ShardManager manager = jda.getShardManager();
        if (manager == null) return true;
        return manager.getShardById(Shards.shardOf(guildId, manager.getShardsTotal())) != null;
    }

    /**
     * Whether the bot is certainly not in this server: this process owns it, its shard is connected (so its server list is loaded),
     * Discord reports no outage for it, and it isn't there. False whenever this process can't tell, so callers try again later.
     */
    public static boolean gone(JDA jda, long guildId) {
        ShardManager manager = jda.getShardManager();
        if (manager == null) return Shards.guild(jda, guildId) == null;
        JDA shard = manager.getShardById(Shards.shardOf(guildId, manager.getShardsTotal()));
        if (shard == null || shard.getStatus() != JDA.Status.CONNECTED) return false;
        return !Shards.unavailable(jda, guildId) && Shards.guild(jda, guildId) == null;
    }
}
