package com.younglings.bot.internal;

import com.younglings.bot.plugin.PluginVisibilityRepository;
import com.younglings.bot.plugin.PluginVisibilityRepository.Shown;
import com.younglings.bot.runescape.ClanMemberRepository.ClanMemberRow;
import com.younglings.bot.runescape.ClanSyncService;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkRepository;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginUsersAdminApiTest {
    private static final long G = 100L;

    private static ClanMemberRow member(String rsn) {
        return new ClanMemberRow(1, G, rsn, "Corporal", null, null, true, 0, 0, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void namesOnlyRosterMembersWhoChoseToShowIt() {
        PluginVisibilityRepository visibility = mock(PluginVisibilityRepository.class);
        PlayerLinkRepository links = mock(PlayerLinkRepository.class);
        ClanSyncService clanSync = mock(ClanSyncService.class);
        when(clanSync.getRoster(G, true)).thenReturn(List.of(member("Main Guy"), member("Alt One"), member("Quiet")));
        when(links.getAllLinks(G)).thenReturn(List.of(
                new PlayerLink(1, G, 501, "Main Guy", "x", null, null), new PlayerLink(2, G, 501, "Alt One", "x", null, null),
                new PlayerLink(3, G, 502, "Quiet", "x", null, null), new PlayerLink(4, G, 999, "Not In Clan", "x", null, null)));
        // only 501 switched it on; the repository is asked about roster members only
        when(visibility.shown(any(Collection.class))).thenAnswer(call -> {
            Collection<Long> asked = call.getArgument(0);
            assertFalse(asked.contains(999L), "someone off the roster is never looked up");
            return List.of(new Shown(501, "RuneSync 0.0.8", Instant.parse("2026-10-21T22:15:00Z")));
        });

        DataObject answer = new PluginUsersAdminApi(visibility, links, clanSync).users(G);

        assertEquals(2, answer.getInt("linkedPlayers"));
        assertEquals(1, answer.getArray("users").length());
        DataObject row = answer.getArray("users").getObject(0);
        assertTrue(row.getString("rsn").contains("Main Guy") && row.getString("rsn").contains("Alt One"), "all of the member's roster names on one row");
        assertEquals("2026-10-21", row.getString("lastSeenDay"), "the day only, not the time");
        assertEquals("RuneSync 0.0.8", row.getString("client"));
        assertFalse(answer.toString().contains("Quiet"), "a member who didn't choose it isn't named");
    }

    @Test
    void anotherRouteIsNotThisOnes() {
        var api = new PluginUsersAdminApi(mock(PluginVisibilityRepository.class), mock(PlayerLinkRepository.class), mock(ClanSyncService.class));
        assertEquals(null, api.route(null, null, "GET", new String[]{"plugin-coverage"}, DataObject::empty));
    }
}
