package com.runeclans.bot.discord;

import com.runeclans.bot.config.BotRole;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T-702: the api role (no gateway) never decides a server is gone, owns no server, and uploads no emoji; only shard 0 uploads. */
class RoleAwareDiscordTest {
    private static final long GUILD = Long.parseLong("1".repeat(18));

    @AfterEach
    void reset() {
        BotRole.install(BotRole.ALL);
    }

    @Test
    void theApiRoleCanNeverTellThatTheBotLeftAServer() {
        JDA restOnly = mock(JDA.class); // no shard manager, no servers: what the api role's connection looks like
        assertTrue(ShardRouting.gone(restOnly, GUILD), "a stand-alone JDA with the server missing: gone, as before");
        BotRole.install(BotRole.API);
        assertFalse(ShardRouting.gone(restOnly, GUILD), "the api role has no server list, so it never drops work for a missing server");
        assertFalse(ShardRouting.owns(restOnly, GUILD));
    }

    @Test
    void onlyTheGatewaysShardZeroUploadsEmojis() {
        JDA zero = mock(JDA.class);
        when(zero.getShardInfo()).thenReturn(new JDA.ShardInfo(0, 2));
        JDA one = mock(JDA.class);
        when(one.getShardInfo()).thenReturn(new JDA.ShardInfo(1, 2));
        assertTrue(EmojiUploads.mayUpload(zero));
        assertFalse(EmojiUploads.mayUpload(one));
        BotRole.install(BotRole.GATEWAY);
        assertTrue(EmojiUploads.mayUpload(zero));
        BotRole.install(BotRole.API);
        assertFalse(EmojiUploads.mayUpload(zero), "the api role only reads the list");
    }
}
