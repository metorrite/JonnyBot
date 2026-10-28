package com.runeclans.bot.commands.rank;

import com.runeclans.bot.commands.rank.RankCommand.Option;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RankCommandTest {
    private static final List<Option> OPTIONS = List.of(new Option(11, "Raids", "Raid Team"), new Option(22, "Skillers", "Skillers"));

    @Test
    void aRoleIsFoundByLabelRoleNameOrIdInAnyCase() {
        assertEquals(11, RankCommand.find(OPTIONS, "raids").roleId());
        assertEquals(11, RankCommand.find(OPTIONS, " RAID team ").roleId());
        assertEquals(22, RankCommand.find(OPTIONS, "22").roleId());
    }

    @Test
    void anythingNotOnTheListIsNotFound() {
        assertNull(RankCommand.find(OPTIONS, "Admin"));
        assertNull(RankCommand.find(OPTIONS, "  "));
        assertNull(RankCommand.find(OPTIONS, null));
        assertNull(RankCommand.find(List.of(), "raids"));
    }
}
