package com.runeclans.bot.rolerules;

import com.runeclans.bot.rolerules.RoleRuleRules.Event;
import com.runeclans.bot.rolerules.RoleRuleRules.Rule;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The role rule SQL against the developer's own local database. Skipped when there isn't one; uses a made-up server id and removes what it adds. */
class RoleRuleRepositoryDatabaseTest {
    private static final long GUILD = 990_000_000_000_000_061L;

    @Test
    void rulesKeepTheirRoleListsAndStayInTheirOwnServer() throws Exception {
        ConnectionSupplier connections = LocalDatabase.supplier();
        RoleRuleRepository repository = new RoleRuleRepository(connections);
        try {
            Rule created = repository.create(new Rule(0, GUILD, "Chat", Event.LOSES, List.of(1L, 2L), List.of(), List.of(30L, 31L), false));
            assertTrue(created.id() > 0);

            Rule read = repository.get(GUILD, created.id()).orElseThrow();
            assertEquals(List.of(1L, 2L), read.when());
            assertEquals(List.of(), read.add());
            assertEquals(List.of(30L, 31L), read.remove());
            assertEquals(Event.LOSES, read.event());
            assertFalse(read.enabled());

            assertTrue(repository.save(new Rule(created.id(), GUILD, "Renamed", Event.GAINS, List.of(1L), List.of(5L), List.of(), true)));
            Rule changed = repository.get(GUILD, created.id()).orElseThrow();
            assertEquals("Renamed", changed.name());
            assertTrue(changed.enabled());

            assertFalse(repository.save(new Rule(created.id(), GUILD + 1, "Hijack", Event.GAINS, List.of(1L), List.of(5L), List.of(), true)), "another server cannot edit it");
            assertFalse(repository.delete(GUILD + 1, created.id()));
            assertTrue(repository.delete(GUILD, created.id()));
        } finally {
            try (Connection c = connections.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.role_rule WHERE guild_id = ?")) {
                s.setLong(1, GUILD);
                s.executeUpdate();
            }
        }
    }
}
