package com.younglings.bot.internal;

import net.dv8tion.jda.api.Permission;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberApiTest {
    @Test
    void aRoleWithOnlyHarmlessPermissionsIsSafe() {
        assertTrue(MemberApi.dangerousIn(EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY)).isEmpty());
    }

    @Test
    void powerfulPermissionsAreFlagged() {
        EnumSet<Permission> found = MemberApi.dangerousIn(EnumSet.of(Permission.MESSAGE_SEND, Permission.BAN_MEMBERS, Permission.ADMINISTRATOR));
        assertEquals(EnumSet.of(Permission.BAN_MEMBERS, Permission.ADMINISTRATOR), found);
    }

    @Test
    void mentionEveryoneAndRoleManagementAreNeverSelfAssignable() {
        assertEquals(EnumSet.of(Permission.MESSAGE_MENTION_EVERYONE, Permission.MANAGE_ROLES),
                MemberApi.dangerousIn(EnumSet.of(Permission.MESSAGE_MENTION_EVERYONE, Permission.MANAGE_ROLES, Permission.VIEW_CHANNEL)));
    }

    private static SiteStatsRepository.CapWeek week(String rsn, String start) {
        return new SiteStatsRepository.CapWeek(rsn, LocalDate.parse(start), true);
    }

    @Test
    void capStreaksCountConsecutiveWeeksAndWhetherTheyAreStillRunning() {
        LocalDate thisWeek = LocalDate.parse("2026-10-07"); // a Wednesday
        List<SiteApi.Streak> streaks = SiteApi.capStreaks(List.of(
                week("Ann", "2026-09-16"), week("Ann", "2026-09-23"), week("Ann", "2026-09-30"), week("Ann", "2026-10-07"), // 4 in a row, still going
                week("Bob", "2026-09-09"), week("Bob", "2026-09-16"), week("Bob", "2026-09-30"),                            // broke the run, ended last-but-one week
                week("Cy", "2026-09-30")), thisWeek);

        assertEquals("Ann", streaks.getFirst().rsn());
        assertEquals(4, streaks.getFirst().longest());
        assertEquals(4, streaks.getFirst().current());

        SiteApi.Streak bob = streaks.stream().filter(s -> s.rsn().equals("Bob")).findFirst().orElseThrow();
        assertEquals(2, bob.longest());
        assertEquals(1, bob.current(), "capped last week, so the run (just that week) is still live");

        SiteApi.Streak cy = streaks.stream().filter(s -> s.rsn().equals("Cy")).findFirst().orElseThrow();
        assertEquals(1, cy.current());
    }

    @Test
    void aRunThatEndedEarlierIsNotCurrent() {
        List<SiteApi.Streak> streaks = SiteApi.capStreaks(List.of(week("Dee", "2026-08-05"), week("Dee", "2026-08-12")), LocalDate.parse("2026-10-07"));
        assertEquals(2, streaks.getFirst().longest());
        assertEquals(0, streaks.getFirst().current());
    }
}
