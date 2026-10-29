package com.runeclans.bot.hubsites;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The switchboard counts the bot as connected in a server only from that server's capability list (T-497). */
class CapabilityReportsTest {
    // fake ids, made at runtime
    private static final long GUILD = Long.parseLong("2".repeat(18));
    private static final long OTHER = Long.parseLong("3".repeat(18));

    private final List<String[]> puts = new ArrayList<>();
    private final CapabilityReports reports = new CapabilityReports((path, body) -> puts.add(new String[]{path, body}));

    @Test
    void onStartTheAppAndEveryServerGetTheList() {
        reports.start(List.of(GUILD, OTHER), true, true);
        assertEquals(3, puts.size());
        assertEquals("/v1/apps/me/capabilities", puts.get(0)[0]);
        assertEquals("/v1/guilds/" + GUILD + "/capabilities", puts.get(1)[0]);
        assertEquals("/v1/guilds/" + OTHER + "/capabilities", puts.get(2)[0]);
        assertEquals(puts.get(0)[1], puts.get(1)[1], "each server gets the same list as the app");
    }

    @Test
    void joiningDeclaresAndLeavingEmptiesTheServer() {
        reports.joined(GUILD, false, false);
        reports.left(GUILD);
        assertEquals("/v1/guilds/" + GUILD + "/capabilities", puts.get(0)[0]);
        assertTrue(DataObject.fromJson(puts.get(0)[1]).getArray("capabilities").length() > 0);
        assertEquals("/v1/guilds/" + GUILD + "/capabilities", puts.get(1)[0]);
        assertEquals(0, DataObject.fromJson(puts.get(1)[1]).getArray("capabilities").length());
    }

    @Test
    void theListFollowsTheIntentsAndNamesTheFeed() {
        String all = CapabilityReports.capabilities(true, true).toString();
        assertTrue(all.contains("\"discord.feed\""));
        assertTrue(all.contains("discord.presence") && all.contains("discord.messages.report") && all.contains("discord.roles.report"));
        String without = CapabilityReports.capabilities(false, false).toString();
        assertFalse(without.contains("discord.presence"));
        assertFalse(without.contains("discord.messages.report"));
        assertTrue(without.contains("\"discord.feed\"") && without.contains("discord.channels.report"));
        assertTrue(without.contains("\"discord.requests\""), "request cards need no privileged intent");
    }
}
