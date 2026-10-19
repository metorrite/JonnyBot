package com.younglings.bot.rolekeep;

import com.younglings.bot.rolekeep.RoleKeepRepository.Temp;
import com.younglings.bot.rolekeep.RoleKeepRules.Source;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleKeepRepositoryDatabaseTest {
    private static final long GUILD = 990_000_000_000_000_051L;

    @Test
    void theSqlKeepsStickyAndManualRolesApartAndTimedRolesExpire() throws Exception {
        ConnectionSupplier connections = LocalDatabase.supplier();
        RoleKeepRepository repository = new RoleKeepRepository(connections);
        try {
            repository.setSticky(GUILD, Set.of(1L, 2L));
            assertEquals(Set.of(1L, 2L), repository.sticky(GUILD));
            repository.setSticky(GUILD, Set.of(2L));
            assertEquals(Set.of(2L), repository.sticky(GUILD));

            assertTrue(repository.keep(GUILD, 5, 2, Source.STICKY, 0));
            assertFalse(repository.keep(GUILD, 5, 2, Source.MANUAL, 9), "already remembered: a sticky entry is not turned into a manual one by accident");
            assertTrue(repository.keep(GUILD, 5, 3, Source.MANUAL, 9));
            assertEquals(2, repository.keptFor(GUILD, 5).size());
            repository.forgetSticky(GUILD, 5);
            assertEquals(1, repository.keptFor(GUILD, 5).size(), "manual entries survive a rejoin");
            assertEquals(1, repository.countManual(GUILD, 5));
            assertTrue(repository.forget(GUILD, 5, 3));

            repository.setTemp(GUILD, 5, 7, Instant.now().minusSeconds(60), 9);
            repository.setTemp(GUILD, 6, 7, Instant.now().plusSeconds(3600), 9);
            assertEquals(1, repository.expired(1000).stream().filter(t -> t.guildId() == GUILD).count());
            assertEquals(0, repository.tempFor(GUILD, 5).size(), "an ended one is not active");
            assertEquals(1, repository.tempFor(GUILD, 6).size());
            Temp ended = repository.expired(1000).stream().filter(t -> t.guildId() == GUILD).findFirst().orElseThrow();
            assertFalse(repository.deleteTemp(GUILD + 1, ended.id()), "another server cannot end it");
            assertTrue(repository.deleteTemp(GUILD, ended.id()));
        } finally {
            for (String table : new String[]{"sticky_role", "role_kept", "role_temp"}) {
                try (Connection c = connections.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ?")) {
                    s.setLong(1, GUILD);
                    s.executeUpdate();
                }
            }
        }
    }
}
