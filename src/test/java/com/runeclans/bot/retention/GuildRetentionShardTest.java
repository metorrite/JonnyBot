package com.runeclans.bot.retention;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.discord.ShardFixtures;
import com.runeclans.bot.rolepanel.RolePanelRepository;
import com.runeclans.bot.starboard.StarboardRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T-700: retention never deletes a server's data because this process can't see it; only the process that runs its shard decides. */
class GuildRetentionShardTest {
    private static final long GUILD = Long.parseLong("1".repeat(18));

    private final GuildRetentionRepository repository = mock(GuildRetentionRepository.class);
    private final BotConfig config = mock(BotConfig.class);
    private final GuildRetentionService service = new GuildRetentionService(repository, config, mock(StarboardRepository.class), mock(RolePanelRepository.class));

    private void departed() {
        when(repository.departedBefore(any(Instant.class))).thenReturn(List.of(GUILD));
        when(config.getGuildDataRetentionDryRun()).thenReturn(true); // a purge shows up as a dry-run count
        when(repository.serverTables()).thenReturn(List.of("polls"));
    }

    @Test
    void aServerOnAnotherShardIsNotPurged() {
        departed();
        service.sweep(ShardFixtures.notOwned(GUILD), 30);
        verify(repository, never()).serverTables();
        verify(repository, never()).cancelDeparture(anyLong());
    }

    @Test
    void aServerWhileItsShardIsConnectingIsNotPurged() {
        departed();
        service.sweep(ShardFixtures.owned(GUILD, JDA.Status.LOADING_SUBSYSTEMS, null), 30);
        verify(repository, never()).serverTables();
    }

    @Test
    void aServerTheBotIsBackInKeepsItsData() {
        departed();
        service.sweep(ShardFixtures.owned(GUILD, JDA.Status.CONNECTED, mock(Guild.class)), 30);
        verify(repository).cancelDeparture(GUILD);
        verify(repository, never()).serverTables();
    }

    @Test
    void aServerTheBotLeftIsPurgedAsBefore() {
        departed();
        service.sweep(ShardFixtures.owned(GUILD, JDA.Status.CONNECTED, null), 30);
        verify(repository).serverTables();
    }
}
