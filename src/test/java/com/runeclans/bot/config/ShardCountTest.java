package com.runeclans.bot.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** T-668: SHARD_COUNT defaults to one shard, the single gateway connection the bot always had. */
class ShardCountTest {
    @Test
    void unsetIsOneShard() {
        assertEquals(1, BotConfig.parseShardCount(null));
        assertEquals(1, BotConfig.parseShardCount("  "));
    }

    @Test
    void aWholeNumberIsUsed() {
        assertEquals(1, BotConfig.parseShardCount("1"));
        assertEquals(4, BotConfig.parseShardCount(" 4 "));
    }

    @Test
    void anythingElseIsOneShard() {
        assertEquals(1, BotConfig.parseShardCount("0"));
        assertEquals(1, BotConfig.parseShardCount("-2"));
        assertEquals(1, BotConfig.parseShardCount("auto"));
        assertEquals(1, BotConfig.parseShardCount("2.5"));
    }
}
