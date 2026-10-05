package com.younglings.bot.tracking;

import com.younglings.bot.runescape.ClanMemberRepository;
import com.younglings.bot.runescape.WeeklyDigestRepository;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeeklyDigestServiceTest {
    private static final long GUILD = 7L;
    // 2026-09-30 is a Wednesday (a Citadel reset day).
    private static final LocalDate WED = LocalDate.of(2026, 9, 30);

    // ---- which Citadel weeks a date range covers ----

    @Test
    void weeksStartOnWednesday() {
        assertEquals(DayOfWeek.WEDNESDAY, WeeklyDigestService.citadelWeekStart(LocalDate.of(2026, 10, 4)).getDayOfWeek());
        assertEquals(WED, WeeklyDigestService.citadelWeekStart(LocalDate.of(2026, 10, 6)));   // Tuesday: still the week that began 9/30
        assertEquals(WED, WeeklyDigestService.citadelWeekStart(WED));                         // the reset day itself starts the new week
        assertEquals(LocalDate.of(2026, 9, 23), WeeklyDigestService.citadelWeekStart(LocalDate.of(2026, 9, 29)));
    }

    @Test
    void aRangeSnapsOutwardToWholeWeeks() {
        var weeks = WeeklyDigestService.weekStarts(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5));
        assertEquals(List.of(LocalDate.of(2026, 9, 23), WED), weeks);
    }

    @Test
    void aRangeNeverReachesPastTheCurrentWeek() {
        var weeks = WeeklyDigestService.weekStarts(LocalDate.of(2026, 9, 23), LocalDate.of(2027, 1, 1), LocalDate.of(2026, 10, 5));
        assertEquals(List.of(LocalDate.of(2026, 9, 23), WED), weeks);
    }

    @Test
    void datesGivenTheWrongWayRoundAreSwapped() {
        var weeks = WeeklyDigestService.weekStarts(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 5));
        assertEquals(List.of(LocalDate.of(2026, 9, 23), WED), weeks);
    }

    @Test
    void aSingleDayIsOneWeek() {
        assertEquals(List.of(WED), WeeklyDigestService.weekStarts(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5)));
    }

    @Test
    void aFutureOnlyRangeFallsBackToTheCurrentWeek() {
        assertEquals(List.of(WED), WeeklyDigestService.weekStarts(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 1), LocalDate.of(2026, 10, 5)));
    }

    // ---- what the viewer says ----

    private WeeklyDigestRepository repository;
    private WeeklyDigestService service;

    @BeforeEach
    void setUp() {
        repository = mock(WeeklyDigestRepository.class);
        ClanMemberRepository clanMembers = mock(ClanMemberRepository.class);
        service = new WeeklyDigestService(repository, clanMembers, mock(TrackingEventRouter.class));

        when(clanMembers.getAll(eq(GUILD), eq(true))).thenReturn(List.of(member("Axley"), member("Berserker87"), member("Idle Ian")));
    }

    private static ClanMemberRepository.ClanMemberRow member(String rsn) {
        return new ClanMemberRepository.ClanMemberRow(1, GUILD, rsn, "Recruit", OffsetDateTime.now(), OffsetDateTime.now(), true, 0, 0, null);
    }

    private static WeeklyDigestRepository.CitadelActivityRow visit(String rsn) {
        return new WeeklyDigestRepository.CitadelActivityRow(rsn, "Visited my Clan Citadel.", "23-Sep-2026 01:12");
    }

    private static WeeklyDigestRepository.CitadelActivityRow cap(String rsn) {
        return new WeeklyDigestRepository.CitadelActivityRow(rsn, "Capped at my Clan Citadel.", "23-Sep-2026 02:12");
    }

    private static String text(List<ContainerChildComponent> children) {
        StringBuilder out = new StringBuilder();
        for (ContainerChildComponent child : children) {
            if (child instanceof TextDisplay display) out.append(display.getContent()).append("\n---\n");
        }
        return out.toString();
    }

    private static LocalDate thisWeek() {
        return WeeklyDigestService.citadelWeekStart(LocalDate.now(ZoneOffset.UTC));
    }

    @Test
    void oneWeekListsWhoVisitedAndCapped() {
        when(repository.getCitadelActivityInWindow(eq(GUILD), any(), any())).thenReturn(List.of(visit("Axley"), cap("Axley"), visit("Berserker87")));

        String text = text(service.citadelSection(GUILD, thisWeek(), LocalDate.now(ZoneOffset.UTC)));

        assertTrue(text.contains("1 capped"), text);
        assertTrue(text.contains("2 visited"), text);
        assertTrue(text.contains("of 3 clan members"), text);
        assertTrue(text.contains("Visited & capped (1)**\nAxley"), text);
        assertTrue(text.contains("Visited only (1)**\nBerserker87"), text);
        assertTrue(text.contains("(so far)"), "the current week is still running");
    }

    @Test
    void aQuietWeekSaysSo() {
        when(repository.getCitadelActivityInWindow(eq(GUILD), any(), any())).thenReturn(List.of());
        assertTrue(text(service.citadelSection(GUILD, thisWeek(), LocalDate.now(ZoneOffset.UTC))).contains("Nobody has visited or capped yet."));
    }

    @Test
    void aPreviousWeekIsNotMarkedAsStillRunning() {
        when(repository.getCitadelActivityInWindow(eq(GUILD), any(), any())).thenReturn(List.of(cap("Axley")));
        LocalDate last = thisWeek().minusWeeks(1);
        assertTrue(!text(service.citadelSection(GUILD, last, last)).contains("(so far)"));
    }

    @Test
    void severalWeeksShowAPerWeekLineAndEachMembersWeekCounts() {
        LocalDate w0 = thisWeek().minusWeeks(2), w1 = thisWeek().minusWeeks(1), w2 = thisWeek();
        when(repository.getCitadelActivityInWindow(eq(GUILD), any(), any())).thenAnswer(invocation -> {
            LocalDate windowStart = ((OffsetDateTime) invocation.getArgument(1)).toLocalDate();
            if (windowStart.equals(w0)) return List.of(visit("Axley"), cap("Axley"));
            if (windowStart.equals(w1)) return List.of(visit("Axley"));
            if (windowStart.equals(w2)) return List.of(visit("Berserker87"), cap("Berserker87"));
            return List.of();
        });

        String text = text(service.citadelSection(GUILD, w0, LocalDate.now(ZoneOffset.UTC)));

        assertTrue(text.contains("3 weeks"), text);
        assertTrue(text.contains("2 different members capped"), text);
        assertTrue(text.contains("Axley — 1 / 2"), text);          // capped in 1 week, visited in 2
        assertTrue(text.contains("Berserker87 — 1 / 1"), text);
        assertTrue(!text.contains("Idle Ian —"), "members with no activity aren't listed individually");
        assertEquals(3, text.lines().filter(line -> line.contains(" capped · ") && line.contains(" visited")).filter(line -> line.startsWith("**")).count(),
                "one line per week");
    }

    @Test
    void aRangeOverTheLimitIsRefusedWithoutQueryingAnything() {
        String text = text(service.citadelSection(GUILD, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 5)));
        assertTrue(text.contains("at most " + WeeklyDigestService.MAX_VIEW_WEEKS), text);
    }
}
