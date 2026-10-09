package com.younglings.bot.runescape;

import com.younglings.bot.configure.GuildSettings;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Who counts as a Guest who never linked an RSN: holders of the role with no link and no waiting request, minus the people who were never meant to. */
class UnverifiedGuestServiceTest {
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    private static UnverifiedGuestService.Candidate guest(long id, int daysAfterT0) {
        return new UnverifiedGuestService.Candidate(id, false, false, false, T0.plusDays(daysAfterT0));
    }

    private static List<Long> ids(List<UnverifiedGuestService.UnverifiedGuest> guests) {
        return guests.stream().map(UnverifiedGuestService.UnverifiedGuest::userId).toList();
    }

    @Test
    void aGuestWithNoLinkAndNoRequestIsListed() {
        assertEquals(List.of(1L), ids(UnverifiedGuestService.select(List.of(guest(1, 0)), Set.of(), Set.of())));
    }

    @Test
    void aLinkedGuestOrOneWithARequestWaitingIsNotListed() {
        var holders = List.of(guest(1, 0), guest(2, 1), guest(3, 2));
        assertEquals(List.of(3L), ids(UnverifiedGuestService.select(holders, Set.of(1L), Set.of(2L))));
    }

    @Test
    void aCancelledOrRejectedRequestDoesNotCountAsWaiting() {
        // only pending attempts are passed in, so someone whose request ended is back on the list
        assertEquals(List.of(1L), ids(UnverifiedGuestService.select(List.of(guest(1, 0)), Set.of(), Set.of())));
    }

    @Test
    void botsAdministratorsAndClanMembersAreLeftOut() {
        var bot = new UnverifiedGuestService.Candidate(1, true, false, false, T0);
        var admin = new UnverifiedGuestService.Candidate(2, false, true, false, T0);
        var clanMember = new UnverifiedGuestService.Candidate(3, false, false, true, T0);
        assertEquals(List.of(4L), ids(UnverifiedGuestService.select(List.of(bot, admin, clanMember, guest(4, 0)), Set.of(), Set.of())));
    }

    @Test
    void theLongestStandingMemberComesFirstAndAnUnknownJoinDateLast() {
        var unknown = new UnverifiedGuestService.Candidate(9, false, false, false, null);
        var ordered = UnverifiedGuestService.select(List.of(unknown, guest(2, 20), guest(1, 5)), Set.of(), Set.of());
        assertEquals(List.of(1L, 2L, 9L), ids(ordered));
    }

    @Test
    void nobodyHoldingTheRoleMeansAnEmptyList() {
        assertEquals(List.of(), UnverifiedGuestService.select(List.of(), Set.of(1L), Set.of(2L)));
    }

    private static GuildSettings settings(Long onboarding, Long nonClan, Long unverified) {
        return new GuildSettings(1, "Younglings", null, null, null, 9L, nonClan, unverified, onboarding, true, "Younglings", null, null);
    }

    @Test
    void theGuestRoleIsTheOnboardingRoleThenTheNonClanRoleThenTheUnverifiedRole() {
        assertEquals(10L, UnverifiedGuestService.guestRoleId(settings(10L, 20L, 30L)));
        assertEquals(20L, UnverifiedGuestService.guestRoleId(settings(null, 20L, 30L)));
        assertEquals(30L, UnverifiedGuestService.guestRoleId(settings(null, null, 30L)));
        assertNull(UnverifiedGuestService.guestRoleId(settings(null, null, null)));
    }
}
