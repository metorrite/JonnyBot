package com.younglings.bot.tracking;

import com.younglings.bot.discord.Containers;
import net.dv8tion.jda.api.components.container.Container;
import com.younglings.bot.runescape.ClanMemberRepository;
import com.younglings.bot.runescape.RuneMetricsDates;
import com.younglings.bot.runescape.WeeklyDigestRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds and sends the two weekly clan digests — joins/leaves ({@link TrackingGroup#WEEKLY_JOINS_LEAVES})
 * and Citadel visits/caps ({@link TrackingGroup#WEEKLY_CITADEL_REPORT}) — for one guild's window.
 * Shared by {@code WeeklyDigestScheduler} (the real weekly run) and the Tracking panel's manual
 * "Send Weekly Digest Now" button, and by {@link WeeklyDigestInteractionListener}'s "Spin Wheel" button,
 * which needs the exact same visited-and-capped list the report itself was built from.
 */
@BService
public class WeeklyDigestService {
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("MMM d");

    private final WeeklyDigestRepository repository;
    private final ClanMemberRepository clanMemberRepository;
    private final TrackingEventRouter router;

    public WeeklyDigestService(WeeklyDigestRepository repository, ClanMemberRepository clanMemberRepository, TrackingEventRouter router) {
        this.repository = repository;
        this.clanMemberRepository = clanMemberRepository;
        this.router = router;
    }

    /** Sends whichever of the two digests actually has something to report for this window — a quiet week posts nothing rather than an empty report. */
    public void sendWeeklyDigests(Guild guild, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        sendJoinsLeaves(guild, windowStart, windowEnd);
        sendCitadelReport(guild, windowStart, windowEnd);
    }

    /**
     * The same Wed-00:01-to-Wed-00:00 UTC window {@code WeeklyDigestScheduler} computes for its real
     * weekly run — "this week's report" for an on-demand send always means the week that most recently
     * completed.
     */
    public static OffsetDateTime[] lastCompletedWindow() {
        OffsetDateTime windowEnd = OffsetDateTime.now(ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY))
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        return new OffsetDateTime[]{windowEnd.minusDays(7).plusMinutes(1), windowEnd};
    }

    /** Just the joins/leaves half — the Tracking panel's "Send Now" calls this or {@link #sendCitadelReport} individually rather than always sending both. Returns {@code false} if the window had nothing to report (so nothing was built). */
    public boolean sendJoinsLeaves(Guild guild, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        long guildId = guild.getIdLong();
        List<WeeklyDigestRepository.RosterEvent> events = repository.getRosterEventsInWindow(guildId, windowStart, windowEnd);
        if (events.isEmpty()) return false;

        List<WeeklyDigestRepository.RosterEvent> joins = events.stream()
                .filter(e -> e.eventType().equals("JOIN")).sorted(Comparator.comparing(WeeklyDigestRepository.RosterEvent::eventAt)).toList();
        List<WeeklyDigestRepository.RosterEvent> leaves = events.stream()
                .filter(e -> e.eventType().equals("LEAVE")).sorted(Comparator.comparing(WeeklyDigestRepository.RosterEvent::eventAt)).toList();

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Weekly Clan Roster — " + formatRange(windowStart, windowEnd)));

        if (!joins.isEmpty()) {
            StringBuilder sb = new StringBuilder("**Joins**\n");
            for (var e : joins) sb.append("🟢 **JOIN** — **").append(e.rsn()).append("** (").append(e.eventAt().format(DISPLAY_DATE)).append(")\n");
            children.add(TextDisplay.of(sb.toString().trim()));
        }
        if (!leaves.isEmpty()) {
            StringBuilder sb = new StringBuilder("**Leaves**\n");
            for (var e : leaves) sb.append("🔴 **LEFT** — **").append(e.rsn()).append("** (").append(e.eventAt().format(DISPLAY_DATE)).append(")\n");
            children.add(TextDisplay.of(sb.toString().trim()));
        }

        router.dispatchContainer(guild, TrackingGroup.WEEKLY_JOINS_LEAVES, Containers.card(Containers.PRIMARY, children));
        return true;
    }

    /** Returns {@code false} if nobody visited or capped in the window (so nothing was built). */
    public boolean sendCitadelReport(Guild guild, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        List<CitadelEntry> entries = computeCitadelEntries(guild.getIdLong(), windowStart, windowEnd);
        if (entries.stream().noneMatch(e -> e.visited || e.capped)) return false;

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Weekly Citadel Report — " + formatRange(windowStart, windowEnd)));

        children.add(TextDisplay.of(totalsLine(entries)));

        StringBuilder sb = new StringBuilder();
        for (CitadelEntry e : entries) {
            sb.append("**").append(e.rsn).append("** — ")
                    .append(e.visited ? "✅ Visited" : "⬜ Visited").append("  ")
                    .append(e.capped ? "✅ Capped" : "⬜ Capped").append("\n");
        }
        children.add(TextDisplay.of(sb.toString().trim()));

        String weekKey = windowEnd.toLocalDate().toString();
        children.add(ActionRow.of(Button.primary("weekly_spin_wheel:" + weekKey, "🎡 Spin Wheel")));

        router.dispatchContainer(guild, TrackingGroup.WEEKLY_CITADEL_REPORT, Containers.card(Containers.PRIMARY, children));
        return true;
    }

    /**
     * The in-progress Citadel week: from the most recent Wednesday reset (00:01 UTC, matching
     * {@link #lastCompletedWindow()}'s start) through right now. Unlike the completed-week window this
     * has no fixed end, so it's only ever a "so far" view.
     */
    public static OffsetDateTime[] currentWindow() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime reset = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY))
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        return new OffsetDateTime[]{reset.plusMinutes(1), now};
    }

    /**
     * Totals plus who's in each group, for an on-demand look at any window — what {@code /citadel}
     * shows. Names only for the people who did something; the rest are just counted, so this stays
     * short enough for one message however large the clan is.
     */
    public Container buildCitadelSummary(long guildId, OffsetDateTime windowStart, OffsetDateTime windowEnd, String title) {
        List<CitadelEntry> entries = computeCitadelEntries(guildId, windowStart, windowEnd);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + title));
        children.add(TextDisplay.of(totalsLine(entries)));

        List<String> both = entries.stream().filter(e -> e.visited && e.capped).map(e -> e.rsn).toList();
        List<String> visitedOnly = entries.stream().filter(e -> e.visited && !e.capped).map(e -> e.rsn).toList();
        List<String> cappedOnly = entries.stream().filter(e -> !e.visited && e.capped).map(e -> e.rsn).toList();
        if (!both.isEmpty()) children.add(TextDisplay.of("**Visited & capped (" + both.size() + ")**\n" + String.join(", ", both)));
        if (!visitedOnly.isEmpty()) children.add(TextDisplay.of("**Visited only (" + visitedOnly.size() + ")**\n" + String.join(", ", visitedOnly)));
        if (!cappedOnly.isEmpty()) children.add(TextDisplay.of("**Capped only (" + cappedOnly.size() + ")**\n" + String.join(", ", cappedOnly)));
        if (both.isEmpty() && visitedOnly.isEmpty() && cappedOnly.isEmpty()) children.add(TextDisplay.of("Nobody has visited or capped yet."));

        return Containers.card(Containers.PRIMARY, children);
    }

    private static String totalsLine(List<CitadelEntry> entries) {
        long capped = entries.stream().filter(e -> e.capped).count();
        long visited = entries.stream().filter(e -> e.visited).count();
        return "**🏰 " + capped + " capped** · **" + visited + " visited** · of " + entries.size() + " clan members";
    }

    /** Every player who both visited and capped in the window — what the "Spin Wheel" button picks from. */
    public List<String> getVisitedAndCappedRsns(long guildId, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        return computeCitadelEntries(guildId, windowStart, windowEnd).stream()
                .filter(e -> e.visited && e.capped)
                .map(e -> e.rsn)
                .toList();
    }

    private record CitadelEntry(String rsn, boolean visited, boolean capped, LocalDateTime sortTime) {}

    /**
     * One entry per *current* active clan member (so someone who left mid-week still shows their
     * activity, but someone who was never in the clan doesn't show up as a false "did nothing" row),
     * ordered: visited-and-capped by time, then visited-only by time, then neither alphabetically
     * (there's no time to sort those by).
     */
    private List<CitadelEntry> computeCitadelEntries(long guildId, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        List<WeeklyDigestRepository.CitadelActivityRow> rows = repository.getCitadelActivityInWindow(guildId, windowStart, windowEnd);
        List<ClanMemberRepository.ClanMemberRow> roster = clanMemberRepository.getAll(guildId, true);

        Map<String, LocalDateTime> visitedAt = new HashMap<>();
        Map<String, LocalDateTime> cappedAt = new HashMap<>();
        for (var row : rows) {
            LocalDateTime parsed = RuneMetricsDates.parse(row.activityDate());
            String lower = row.rsn().toLowerCase(Locale.ROOT);
            if (row.activityText().startsWith("Visited")) {
                visitedAt.merge(lower, parsed, (a, b) -> a.isAfter(b) ? a : b);
            } else if (row.activityText().startsWith("Capped")) {
                cappedAt.merge(lower, parsed, (a, b) -> a.isAfter(b) ? a : b);
            }
        }

        List<CitadelEntry> entries = new ArrayList<>();
        for (var member : roster) {
            String lower = member.rsn().toLowerCase(Locale.ROOT);
            boolean visited = visitedAt.containsKey(lower);
            boolean capped = cappedAt.containsKey(lower);
            LocalDateTime sortTime = capped ? cappedAt.get(lower) : visitedAt.get(lower);
            entries.add(new CitadelEntry(member.rsn(), visited, capped, sortTime));
        }

        entries.sort(Comparator
                .comparingInt(WeeklyDigestService::participationRank)
                .thenComparing(e -> e.sortTime != null ? e.sortTime : LocalDateTime.MIN)
                .thenComparing(e -> e.rsn, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    private static int participationRank(CitadelEntry e) {
        if (e.visited && e.capped) return 0;
        if (e.visited) return 1;
        return 2;
    }

    private static String formatRange(OffsetDateTime start, OffsetDateTime end) {
        DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);
        return start.toLocalDate().format(formatter) + " – " + end.toLocalDate().minusDays(1).format(formatter);
    }
}
