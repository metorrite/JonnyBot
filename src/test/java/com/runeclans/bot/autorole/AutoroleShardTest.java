package com.runeclans.bot.autorole;

import com.runeclans.bot.discord.ShardFixtures;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T-700: a delayed autorole for a server on another process's shard waits for that process; one for a server the bot left is dropped. */
class AutoroleShardTest {
    private static final long GUILD = Long.parseLong("1".repeat(18));
    private static final long USER = Long.parseLong("2".repeat(18));

    private final AutoroleRepository repository = mock(AutoroleRepository.class);
    private final AutoroleService service = new AutoroleService(repository);

    @Test
    void aPendingRoleOnAnotherShardIsKept() {
        when(repository.duePending(any(Instant.class), anyInt())).thenReturn(List.of(new AutoroleRepository.Pending(GUILD, USER)));
        service.sweep(ShardFixtures.notOwned(GUILD));
        verify(repository, never()).removePending(anyLong(), anyLong());
    }

    @Test
    void aPendingRoleInAServerTheBotLeftIsDropped() {
        when(repository.duePending(any(Instant.class), anyInt())).thenReturn(List.of(new AutoroleRepository.Pending(GUILD, USER)));
        service.sweep(ShardFixtures.owned(GUILD, JDA.Status.CONNECTED, null));
        verify(repository).removePending(GUILD, USER);
    }
}
