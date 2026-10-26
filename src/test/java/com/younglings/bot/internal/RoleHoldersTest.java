package com.younglings.bot.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoleHoldersTest {
    @Test
    void countsEachRolesHoldersAmongThePeopleSeen() {
        RoleHolders h = RoleHolders.count(List.of(List.of(1L, 2L), List.of(2L), List.of()));
        assertEquals(3, h.seen());
        assertEquals(1, h.holders(1L));
        assertEquals(2, h.holders(2L));
        assertEquals(0, h.holders(3L), "a role nobody holds");
    }
}
