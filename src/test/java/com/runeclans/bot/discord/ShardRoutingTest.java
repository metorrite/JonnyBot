package com.runeclans.bot.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T-700 (SCALING-STATE M9): a server missing from this process only counts as gone when this process runs its shard and can see. */
class ShardRoutingTest {
    private static final long GUILD = Long.parseLong("1".repeat(18));

    @Test
    void aStandAloneJdaOwnsEverythingAndAMissingServerIsGoneAsBefore() {
        JDA jda = mock(JDA.class);
        assertTrue(ShardRouting.owns(jda, GUILD));
        assertTrue(ShardRouting.gone(jda, GUILD));
        when(jda.getGuildById(GUILD)).thenReturn(mock(Guild.class));
        assertFalse(ShardRouting.gone(jda, GUILD));
    }

    @Test
    void aServerOnAShardThisProcessDoesNotRunIsNeitherOwnedNorGone() {
        JDA jda = ShardFixtures.notOwned(GUILD);
        assertFalse(ShardRouting.owns(jda, GUILD));
        assertFalse(ShardRouting.gone(jda, GUILD), "not mine is not the same as the bot left");
    }

    @Test
    void anOwnedConnectedShardWithoutTheServerMeansTheBotLeft() {
        JDA jda = ShardFixtures.owned(GUILD, JDA.Status.CONNECTED, null);
        assertTrue(ShardRouting.owns(jda, GUILD));
        assertTrue(ShardRouting.gone(jda, GUILD));
    }

    @Test
    void anOwnedServerThatIsThereIsNotGone() {
        JDA jda = ShardFixtures.owned(GUILD, JDA.Status.CONNECTED, mock(Guild.class));
        assertFalse(ShardRouting.gone(jda, GUILD));
    }

    @Test
    void aShardStillConnectingCannotTellYet() {
        JDA jda = ShardFixtures.owned(GUILD, JDA.Status.LOADING_SUBSYSTEMS, null);
        assertTrue(ShardRouting.owns(jda, GUILD));
        assertFalse(ShardRouting.gone(jda, GUILD), "the server list isn't loaded yet");
    }

    @Test
    void aServerInADiscordOutageIsNotGone() {
        JDA jda = ShardFixtures.owned(GUILD, JDA.Status.CONNECTED, null, true);
        assertFalse(ShardRouting.gone(jda, GUILD));
    }
}
