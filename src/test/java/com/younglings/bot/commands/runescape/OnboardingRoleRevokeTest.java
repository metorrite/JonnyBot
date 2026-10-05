package com.younglings.bot.commands.runescape;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a rejected or withdrawn request takes the Guest role back — and the cases where it must not. */
class OnboardingRoleRevokeTest {
    private static final long GUEST = 1, MEMBER = 2, COSMETIC = 3;

    @Test
    void aRejectedNewcomerLosesGuest() {
        assertTrue(RsInteractionListener.shouldRevokeOnboardingRole(GUEST, false, false, Set.of(GUEST, COSMETIC)));
    }

    @Test
    void someoneWithAVerifiedLinkKeepsTheirRolesEvenIfAnotherRequestIsRejected() {
        assertFalse(RsInteractionListener.shouldRevokeOnboardingRole(GUEST, true, false, Set.of(GUEST)));
    }

    @Test
    void anAdministratorIsNeverLockedOut() {
        assertFalse(RsInteractionListener.shouldRevokeOnboardingRole(GUEST, false, true, Set.of(GUEST)));
    }

    @Test
    void nothingHappensWhenTheyDoNotHoldTheRoleOrNoneIsConfigured() {
        assertFalse(RsInteractionListener.shouldRevokeOnboardingRole(GUEST, false, false, Set.of(MEMBER)));
        assertFalse(RsInteractionListener.shouldRevokeOnboardingRole(null, false, false, Set.of(GUEST)));
    }
}
