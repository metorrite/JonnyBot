package com.runeclans.bot.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.sharding.ShardManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T-668: bot-wide lookups ask every shard, and a stand-alone JDA (one shard, tests) answers exactly as before. */
class ShardsTest {
    private static final long GUILD = Long.parseLong("1".repeat(18));
    private static final long USER = Long.parseLong("2".repeat(18));

    @Test
    void aStandAloneJdaIsAskedDirectly() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        User user = mock(User.class);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        when(jda.getGuilds()).thenReturn(List.of(guild));
        when(jda.getUserById(USER)).thenReturn(user);
        when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(jda.getGatewayPing()).thenReturn(42L);

        assertSame(guild, Shards.guild(jda, GUILD));
        assertEquals(List.of(guild), Shards.guilds(jda));
        assertSame(user, Shards.user(jda, USER));
        assertTrue(Shards.allConnected(jda));
        assertFalse(Shards.unavailable(jda, GUILD));
        assertEquals(JDA.Status.CONNECTED, Shards.status(jda));
        assertEquals(42L, Shards.gatewayPing(jda));
        assertEquals(1, Shards.total(jda));
    }

    @Test
    void aShardAsksTheShardManagerForServersOnOtherShards() {
        ShardManager manager = mock(ShardManager.class);
        JDA shard0 = mock(JDA.class);
        JDA shard1 = mock(JDA.class);
        Guild other = mock(Guild.class);
        when(shard0.getShardManager()).thenReturn(manager);
        when(shard0.getGuildById(GUILD)).thenReturn(null); // shard 0 doesn't hold it
        when(manager.getGuildById(GUILD)).thenReturn(other);
        when(manager.getGuilds()).thenReturn(List.of(other));
        when(manager.getShards()).thenReturn(List.of(shard0, shard1));
        when(manager.getShardsTotal()).thenReturn(2);
        when(manager.getShardById(Shards.shardOf(GUILD, 2))).thenReturn(Shards.shardOf(GUILD, 2) == 0 ? shard0 : shard1);
        when(shard0.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(shard1.getStatus()).thenReturn(JDA.Status.AWAITING_LOGIN_CONFIRMATION);

        assertSame(other, Shards.guild(shard0, GUILD));
        assertEquals(List.of(other), Shards.guilds(shard0));
        assertFalse(Shards.allConnected(shard0), "one shard still logging in means not every server is known yet");
        assertEquals(JDA.Status.AWAITING_LOGIN_CONFIRMATION, Shards.status(shard0));
        assertEquals(2, Shards.total(shard0));
    }

    @Test
    void aServerOnAShardThisProcessDoesNotRunCountsAsUnavailable() {
        ShardManager manager = mock(ShardManager.class);
        JDA shard = mock(JDA.class);
        when(shard.getShardManager()).thenReturn(manager);
        when(manager.getShardsTotal()).thenReturn(2);
        when(manager.getShardById(Shards.shardOf(GUILD, 2))).thenReturn(null);
        assertTrue(Shards.unavailable(shard, GUILD));
    }

    @Test
    void discordsShardFormula() {
        assertEquals(0, Shards.shardOf(GUILD, 1));
        assertEquals(0, Shards.shardOf(GUILD, 0));
        long id = 5L << 22;
        assertEquals(1, Shards.shardOf(id, 2));
        assertEquals(2, Shards.shardOf(id, 3));
    }

    /** "Every JDA holder goes through the shard manager": one shard's JDA only knows that shard's servers. */
    @Test
    void mainCodeNeverAsksOneShardForBotWideLookups() throws IOException {
        Pattern botWide = Pattern.compile("\\.(getGuildById|getUserById|getMutualGuilds|isUnavailable)\\(|\\.(getGuilds|getUserCache|getGuildCache)\\(\\)");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java") && !p.endsWith("Shards.java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.trim().startsWith("*") || line.trim().startsWith("//")) continue;
                    if (botWide.matcher(line).find()) offenders.add(file + ":" + (i + 1));
                }
            }
        }
        assertEquals(List.of(), offenders, "use com.runeclans.bot.discord.Shards for bot-wide lookups");
    }

    @Test
    void theBotLogsInThroughTheShardManager() throws IOException {
        String bot = Files.readString(Path.of("src/main/java/com/runeclans/bot/Bot.java"));
        assertTrue(bot.contains("createLightSharded("), "Bot must build a DefaultShardManagerBuilder");
        assertFalse(bot.contains("createLight(botConfig"), "Bot must not build a single JDA");
        assertTrue(bot.contains(".setShardsTotal(shardCount)"), "the shard total comes from SHARD_COUNT, never Discord's recommendation");
        assertTrue(bot.contains(".setMemberCachePolicy(MemberCachePolicy.ONLINE)"), "same member cache as before");
        assertTrue(bot.contains(".setRawEventsEnabled(true)"), "PresenceMemberLoader needs raw PRESENCE_UPDATE events");
    }
}
