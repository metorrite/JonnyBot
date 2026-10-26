package com.younglings.bot.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Bot-wide Discord lookups that work the same with one shard or many (T-668, docs SCALING section 9 P1).
 *
 * <p>The bot logs in through a {@link ShardManager} ({@code SHARD_COUNT}, default 1). A {@link JDA} is one shard and only knows the servers
 * that shard holds, so "find server X", "every server" and "user X from the cache" must ask the shard manager, never one shard. Every holder
 * of a {@code JDA} (the internal API, schedulers, guild lookups) goes through these helpers. A {@code JDA} built without a shard manager
 * (tests, mocks) is asked directly, so with one shard every answer is exactly what {@code jda.getGuildById(...)} etc. gave before.
 *
 * <p>Per-server work (a listener's {@code event.getJDA()}, {@code guild.getJDA()} for that guild's own channels and members) stays on the
 * shard: that shard is the one that holds the server.
 */
public final class Shards {
    private Shards() {
    }

    /** The shard manager this shard belongs to, or null for a stand-alone {@code JDA}. */
    public static @Nullable ShardManager manager(JDA jda) {
        return jda.getShardManager();
    }

    /** The server with this id from whichever shard holds it, or null. */
    public static @Nullable Guild guild(JDA jda, long guildId) {
        ShardManager manager = jda.getShardManager();
        return manager == null ? jda.getGuildById(guildId) : manager.getGuildById(guildId);
    }

    /** The server with this id from whichever shard holds it, or null (the id must be a valid snowflake, as with JDA). */
    public static @Nullable Guild guild(JDA jda, String guildId) {
        ShardManager manager = jda.getShardManager();
        return manager == null ? jda.getGuildById(guildId) : manager.getGuildById(guildId);
    }

    /** Every server on every shard. */
    public static List<Guild> guilds(JDA jda) {
        ShardManager manager = jda.getShardManager();
        return manager == null ? jda.getGuilds() : manager.getGuilds();
    }

    /** A cached user from any shard, or null. */
    public static @Nullable User user(JDA jda, long userId) {
        ShardManager manager = jda.getShardManager();
        return manager == null ? jda.getUserById(userId) : manager.getUserById(userId);
    }

    /** A cached user from any shard, or null. */
    public static @Nullable User user(JDA jda, String userId) {
        ShardManager manager = jda.getShardManager();
        return manager == null ? jda.getUserById(userId) : manager.getUserById(userId);
    }

    /** Every cached user on every shard. */
    public static SnowflakeCacheView<User> userCache(JDA jda) {
        ShardManager manager = jda.getShardManager();
        return manager == null ? jda.getUserCache() : manager.getUserCache();
    }

    /** The servers the bot shares with this user, on every shard. */
    public static List<Guild> mutualGuilds(User user) {
        JDA jda = user.getJDA();
        ShardManager manager = jda == null ? null : jda.getShardManager();
        return manager == null ? user.getMutualGuilds() : manager.getMutualGuilds(user);
    }

    /** A channel of this type from any shard, or null. */
    public static <T extends Channel> @Nullable T channel(JDA jda, Class<T> type, long channelId) {
        ShardManager manager = jda.getShardManager();
        return manager == null ? jda.getChannelById(type, channelId) : manager.getChannelById(type, channelId);
    }

    /** A text channel from any shard, or null. */
    public static @Nullable TextChannel textChannel(JDA jda, long channelId) {
        ShardManager manager = jda.getShardManager();
        return manager == null ? jda.getTextChannelById(channelId) : manager.getTextChannelById(channelId);
    }

    /** Whether every shard is connected (with one shard: whether it is). */
    public static boolean allConnected(JDA jda) {
        ShardManager manager = jda.getShardManager();
        if (manager == null) return jda.getStatus() == JDA.Status.CONNECTED;
        List<JDA> shards = manager.getShards();
        return !shards.isEmpty() && shards.stream().allMatch(s -> s.getStatus() == JDA.Status.CONNECTED);
    }

    /**
     * Whether Discord says this server is in an outage, asked of the shard that holds it. A shard this process doesn't run counts as
     * unavailable, so a caller never takes "not in my cache" for "the bot isn't in that server".
     */
    public static boolean unavailable(JDA jda, long guildId) {
        ShardManager manager = jda.getShardManager();
        if (manager == null) return jda.isUnavailable(guildId);
        JDA shard = manager.getShardById(shardOf(guildId, manager.getShardsTotal()));
        return shard == null || shard.isUnavailable(guildId);
    }

    /** The bot-wide gateway status: CONNECTED when every shard is, else the first shard's status that isn't. */
    public static JDA.Status status(JDA jda) {
        ShardManager manager = jda.getShardManager();
        if (manager == null) return jda.getStatus();
        for (JDA shard : manager.getShards()) {
            if (shard.getStatus() != JDA.Status.CONNECTED) return shard.getStatus();
        }
        return manager.getShards().isEmpty() ? jda.getStatus() : JDA.Status.CONNECTED;
    }

    /** Gateway ping in ms: the average over the shards (with one shard: its ping). */
    public static long gatewayPing(JDA jda) {
        ShardManager manager = jda.getShardManager();
        return manager == null ? jda.getGatewayPing() : Math.round(manager.getAverageGatewayPing());
    }

    /** How many shards the bot runs in total (1 for a stand-alone {@code JDA}). */
    public static int total(JDA jda) {
        ShardManager manager = jda.getShardManager();
        return manager == null ? 1 : manager.getShardsTotal();
    }

    /** Discord's shard formula: the shard that receives a server's events. */
    public static int shardOf(long guildId, int shardsTotal) {
        if (shardsTotal <= 1) return 0;
        return (int) ((guildId >>> 22) % shardsTotal);
    }
}
