package com.younglings.bot.retention;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuildPurgeTest {
    @Test
    void everyTableIsEmptiedAndTheRowsAreCounted() {
        Map<String, Integer> rows = Map.of("welcome_config", 1, "ticket", 4, "tracking_destination", 3);

        GuildPurge.Result result = GuildPurge.purge(List.of("welcome_config", "ticket", "tracking_destination"), 99L, (table, guild) -> rows.get(table));

        assertTrue(result.complete());
        assertEquals(8, result.totalRows());
    }

    @Test
    void theListsOfTrustedAndPremiumServersAndTheBookkeepingAreNeverTouched() {
        Set<String> touched = new HashSet<>();

        GuildPurge.purge(List.of("beta_guild", "premium_guild", "guild_departure", "beta_guild_seeded", "ticket"), 99L, (table, guild) -> {
            touched.add(table);
            return 0;
        });

        assertEquals(Set.of("ticket"), touched);
    }

    @Test
    void aTableThatRefusesUntilItsChildrenAreGoneIsRetriedAfterThem() {
        Set<String> gone = new HashSet<>();
        GuildPurge.RowDeleter deleter = (table, guild) -> {
            if (table.equals("ticket") && !gone.contains("ticket_answer")) throw new SQLException("ticket_answer still points at ticket");
            gone.add(table);
            return 1;
        };

        GuildPurge.Result result = GuildPurge.purge(List.of("ticket", "ticket_answer"), 99L, deleter);

        assertTrue(result.complete(), "ticket should succeed in a later round");
        assertEquals(2, result.totalRows());
    }

    @Test
    void aTableThatNeverSucceedsIsReportedNotForced() {
        GuildPurge.RowDeleter deleter = (table, guild) -> {
            if (table.equals("stuck")) throw new SQLException("foreign key violation");
            return 2;
        };

        GuildPurge.Result result = GuildPurge.purge(List.of("stuck", "fine"), 99L, deleter);

        assertFalse(result.complete());
        assertEquals(Set.of("stuck"), result.refused().keySet());
        assertEquals(2, result.totalRows());
    }

    @Test
    void theServerIdIsPassedThrough() {
        Map<String, Long> seen = new HashMap<>();

        GuildPurge.purge(List.of("a"), 12345L, (table, guild) -> {
            seen.put(table, guild);
            return 0;
        });

        assertEquals(12345L, seen.get("a"));
    }
}
