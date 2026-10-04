package com.younglings.bot.runescape;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClanVerificationServiceTest {
    private static RuneScapeApiClient.ClanMember member(String rsn, String rank) {
        return new RuneScapeApiClient.ClanMember(rsn, rank, 0, 0);
    }

    private static final List<RuneScapeApiClient.ClanMember> ROSTER = List.of(
            member("Metorrite", "Owner"), member("Some Admin", "Admin"), member("Low Guy", "Captain"), member("Deputy Dan", "Deputy Owner"));

    @Test
    void everythingMetWhenAnAdminRankedMemberIsLinked() {
        var result = ClanVerificationService.evaluate("Younglings", ROSTER, List.of("some_admin"));
        assertTrue(result.allMet());
    }

    @Test
    void ranksAboveAdminAlsoQualify() {
        assertTrue(ClanVerificationService.evaluate("C", ROSTER, List.of("Metorrite")).allMet());
        assertTrue(ClanVerificationService.evaluate("C", ROSTER, List.of("Deputy Dan")).allMet());
    }

    @Test
    void aRankBelowAdminFailsOnlyTheRankCheck() {
        var result = ClanVerificationService.evaluate("C", ROSTER, List.of("Low Guy"));
        assertFalse(result.allMet());
        assertEquals(List.of(true, true, true, false), result.checks().stream().map(ClanVerificationService.Check::met).toList());
    }

    @Test
    void anUnknownClanFailsEverythingThatDependsOnIt() {
        var result = ClanVerificationService.evaluate("Nope", List.of(), List.of("Metorrite"));
        assertEquals(List.of(false, true, false, false), result.checks().stream().map(ClanVerificationService.Check::met).toList());
    }

    @Test
    void noLinkedNameFailsTheLinkMembershipAndRankChecks() {
        var result = ClanVerificationService.evaluate("C", ROSTER, List.of());
        assertEquals(List.of(true, false, false, false), result.checks().stream().map(ClanVerificationService.Check::met).toList());
    }

    @Test
    void aLinkedNameThatIsNotInTheClanIsNotAMember() {
        var result = ClanVerificationService.evaluate("C", ROSTER, List.of("Stranger"));
        assertEquals(List.of(true, true, false, false), result.checks().stream().map(ClanVerificationService.Check::met).toList());
    }

    @Test
    void jagexNonBreakingSpacesAndUnderscoresMatchPlainSpaces() {
        var roster = List.of(member("Some Admin", "Deputy Owner"));
        assertTrue(ClanVerificationService.evaluate("C", roster, List.of("Some_Admin")).allMet());
    }

    @Test
    void anUnrecognizedRankNeverQualifies() {
        assertFalse(ClanVerificationService.isAtLeast("Mystery", "Admin"));
        assertTrue(ClanVerificationService.isAtLeast("Admin", "Admin"));
        assertFalse(ClanVerificationService.isAtLeast("General", "Admin"));
    }
}
