package com.younglings.bot.internal;

import com.younglings.bot.alliance.AllianceRepository;
import com.younglings.bot.clanprofile.ClanProfileService;
import com.younglings.bot.clanprofile.JoinLinks;
import com.younglings.bot.events.BingoTemplate;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.EventTemplates;
import com.younglings.bot.events.HubModerationRepository;
import com.younglings.bot.events.PluginObjectiveMatcher;
import com.younglings.bot.runescape.PlayerLinkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The public event detail's {@code xpTiles} (T-543): the bingo's XP tile labels while XP tiles are counted, nothing while the switch is off. */
class HubEventsXpTilesTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private final EventService service = mock(EventService.class);
    private final HubEventsApi api = new HubEventsApi(mock(EventRepository.class), service, mock(EventTemplates.class), mock(JoinLinks.class), mock(ClanProfileService.class),
            mock(AllianceRepository.class), mock(PlayerLinkService.class), mock(HubModerationRepository.class),
            mock(com.younglings.bot.events.EventCohostRepository.class), mock(com.younglings.bot.permission.DashboardAccess.class), mock(MemberEventsApi.class));

    @AfterEach
    void offAgain() {
        PluginObjectiveMatcher.offerXpTiles(false);
    }

    private Event bingo() {
        Instant at = Instant.parse("2026-10-20T18:00:00Z");
        when(service.objectives(1)).thenReturn(List.of(new ObjectiveSpec("t1", "Gain 1M Fletching XP", "", 1, Evidence.EITHER, 1, 1, List.of()),
                new ObjectiveSpec("t2", "Nex: Torva boots", "", 1, Evidence.EITHER, 1, 1, List.of())));
        return new Event(1, GUILD, BingoTemplate.KEY, "Bingo", "", Status.RUNNING, null, ScoringMode.INDIVIDUAL, at, at.plusSeconds(86_400), 0, "{}", 1, at, null, false);
    }

    @Test
    void theDetailListsXpTilesOnlyWhileTheyAreCounted() {
        Event e = bingo();
        assertNull(api.xpTilesOf(e), "off (the default): the page shows nothing");
        PluginObjectiveMatcher.offerXpTiles(true);
        assertEquals("[\"Gain 1M Fletching XP\"]", api.xpTilesOf(e).toString());
    }
}
