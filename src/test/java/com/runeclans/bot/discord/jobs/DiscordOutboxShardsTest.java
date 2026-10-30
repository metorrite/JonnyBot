package com.runeclans.bot.discord.jobs;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.sharding.ShardManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Which servers' jobs a process may claim (T-696, docs/SCALING-STATE.md M4). */
class DiscordOutboxShardsTest {
    @Test
    void everyServerWithoutAShardManagerOrWhenEveryShardRunsHere() {
        assertNull(DiscordOutbox.ownShards(null));
        JDA standAlone = mock(JDA.class);
        assertNull(DiscordOutbox.ownShards(standAlone), "tests and a plain JDA: no filter");

        JDA one = shard(0);
        manager(one, 1, one);
        assertNull(DiscordOutbox.ownShards(one), "one shard is today's bot: no filter");

        JDA zero = shard(0);
        JDA first = shard(1);
        manager(zero, 2, zero, first);
        assertNull(DiscordOutbox.ownShards(zero));
    }

    @Test
    void onlyTheShardsThisProcessRuns() {
        JDA two = shard(2);
        JDA three = shard(3);
        manager(two, 4, three, two);
        DiscordJobRepository.ShardSet set = DiscordOutbox.ownShards(two);
        assertEquals(4, set.total());
        assertEquals(List.of(2, 3), set.owned());
    }

    private static JDA shard(int id) {
        JDA jda = mock(JDA.class);
        when(jda.getShardInfo()).thenReturn(new JDA.ShardInfo(id, 4));
        return jda;
    }

    private static ShardManager manager(JDA asker, int total, JDA... running) {
        ShardManager manager = mock(ShardManager.class);
        when(manager.getShardsTotal()).thenReturn(total);
        when(manager.getShards()).thenReturn(List.of(running));
        when(asker.getShardManager()).thenReturn(manager);
        return manager;
    }
}
