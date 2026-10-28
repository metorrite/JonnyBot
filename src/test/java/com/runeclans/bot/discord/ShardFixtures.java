package com.runeclans.bot.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.sharding.ShardManager;
import org.jspecify.annotations.Nullable;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Two-shard set-ups for T-700 tests: the JDA a lane holds, seen from a process that does or doesn't run the server's shard. */
public final class ShardFixtures {
    private ShardFixtures() {
    }

    /** A process running only the other shard: it can't see {@code guildId} and must not decide anything about it. */
    public static JDA notOwned(long guildId) {
        ShardManager manager = manager();
        JDA mine = mock(JDA.class);
        when(mine.getShardManager()).thenReturn(manager);
        when(manager.getShardById(Shards.shardOf(guildId, 2))).thenReturn(null);
        when(manager.getGuildById(guildId)).thenReturn(null);
        return mine;
    }

    /** A process running the server's shard, in {@code status}, holding {@code guild} (null: the bot isn't in it) and no outage. */
    public static JDA owned(long guildId, JDA.Status status, @Nullable Guild guild) {
        return owned(guildId, status, guild, false);
    }

    /** As {@link #owned(long, JDA.Status, Guild)}, with Discord reporting an outage for the server or not. */
    public static JDA owned(long guildId, JDA.Status status, @Nullable Guild guild, boolean unavailable) {
        ShardManager manager = manager();
        JDA shard = mock(JDA.class);
        when(shard.getShardManager()).thenReturn(manager);
        when(shard.getStatus()).thenReturn(status);
        when(shard.isUnavailable(guildId)).thenReturn(unavailable);
        when(manager.getShardById(Shards.shardOf(guildId, 2))).thenReturn(shard);
        when(manager.getGuildById(guildId)).thenReturn(guild);
        return shard;
    }

    private static ShardManager manager() {
        ShardManager manager = mock(ShardManager.class);
        when(manager.getShardsTotal()).thenReturn(2);
        return manager;
    }
}
