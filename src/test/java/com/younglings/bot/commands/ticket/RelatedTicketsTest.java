package com.younglings.bot.commands.ticket;

import com.younglings.bot.commands.ticket.RelatedTickets.Candidate;
import com.younglings.bot.ticket.HelpExtras;
import com.younglings.bot.ticket.TicketModels.Answer;
import com.younglings.bot.ticket.TicketModels.PanelRoles;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Finding other open tickets for the same group boss, and who may link or merge them. */
class RelatedTicketsTest {
    private static final HelpExtras ON = new HelpExtras(1, false, 24, 1, true, "Vorago, Solak");

    private static Candidate c(long id, int number, long requester, String boss) {
        return new Candidate(id, number, 100L + id, requester, boss);
    }

    @Test
    void offByDefaultSoNothingIsEverOffered() {
        assertTrue(RelatedTickets.siblings(HelpExtras.defaults(1), c(1, 1, 10, "Vorago"), List.of(c(2, 2, 11, "Vorago"))).isEmpty());
    }

    @Test
    void anotherOpenTicketForTheSameGroupBossIsOffered() {
        var found = RelatedTickets.siblings(ON, c(3, 3, 12, "vorago"), List.of(c(1, 1, 10, "Vorago"), c(2, 2, 11, "Solak"), c(4, 4, 13, "VORAGO")));
        assertEquals(List.of(1L, 4L), found.stream().map(Candidate::ticketId).toList(), "same boss ignoring case, not the other boss, oldest first");
    }

    @Test
    void aBossThatIsNotGroupContentIsNeverMatched() {
        assertTrue(RelatedTickets.siblings(ON, c(3, 3, 12, "Araxxor"), List.of(c(1, 1, 10, "Araxxor"))).isEmpty());
    }

    @Test
    void aMembersOwnOtherTicketAndTheTicketItselfDoNotCount() {
        assertTrue(RelatedTickets.siblings(ON, c(3, 3, 12, "Vorago"), List.of(c(3, 3, 12, "Vorago"), c(4, 4, 12, "Vorago"))).isEmpty());
    }

    @Test
    void atMostTwoAreShownBecauseEachGetsTwoButtons() {
        var many = List.of(c(1, 1, 10, "Vorago"), c(2, 2, 11, "Vorago"), c(4, 4, 13, "Vorago"));
        assertEquals(RelatedTickets.MAX_SHOWN, RelatedTickets.siblings(ON, c(9, 9, 99, "Vorago"), many).size());
    }

    @Test
    void theNoticeNamesTheOtherTicketsAndTheChoices() {
        String text = RelatedTickets.notice(c(3, 3, 12, "Vorago"), List.of(c(1, 1, 10, "Vorago")));
        assertTrue(text.contains("#0001") && text.contains("<#101>") && text.contains("<@10>") && text.contains("Link") && text.contains("Merge"));
    }

    @Test
    void buttonIdsRoundTripAndBadOnesAreRefused() {
        assertArrayEquals(new long[]{7, 9}, RelatedTickets.parse(RelatedTickets.linkId(7, 9)));
        assertArrayEquals(new long[]{7, 9}, RelatedTickets.parse(RelatedTickets.mergeId(7, 9)));
        assertNull(RelatedTickets.parse("ticket_rel_link:7"));
        assertNull(RelatedTickets.parse("ticket_rel_link:a:b"));
    }

    @Test
    void theBossComesFromTheQuestionMarkedAsTheBoss() {
        var fields = HelpPanels.ca(1, "CA Help").fields();
        assertEquals("Vorago", RelatedTickets.bossOf(fields, List.of(new Answer("Which boss?", "Vorago"))));
        assertEquals("", RelatedTickets.bossOf(fields, List.of(new Answer("Tier", "Master"))));
    }

    @Test
    void theSettingsNeedAGroupBossWhenSwitchedOn() {
        assertTrue(ON.problems().isEmpty());
        assertFalse(new HelpExtras(1, false, 24, 1, true, " , ").problems().isEmpty());
        assertTrue(new HelpExtras(1, false, 24, 1, false, "").problems().isEmpty());
        assertEquals(List.of("Vorago", "Solak"), ON.groupBossList());
    }

    @Test
    void onlyStaffAndAdminsMayMerge() {
        PanelRoles roles = new PanelRoles(Set.of(10L), Set.of(20L), Set.of());
        assertTrue(TicketRelatedService.mayMerge(Set.of(), true, roles));
        assertTrue(TicketRelatedService.mayMerge(Set.of(20L), false, roles));
        assertFalse(TicketRelatedService.mayMerge(Set.of(10L), false, roles), "a helper may link but not merge");
        assertFalse(TicketRelatedService.mayMerge(Set.of(99L), false, roles));
    }
}
