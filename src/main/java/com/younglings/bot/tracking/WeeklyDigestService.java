package com.younglings.bot.tracking;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.runescape.ClanMemberRepository;
import com.younglings.bot.runescape.WeeklyDigestRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
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
    // RuneMetrics' own raw activity_date format ("23-Sep-2026 23:30") — parsed here purely to order
    // players *relative to each other* within one report; never stored or compared across guilds/weeks
    // as an absolute instant, since the timezone it's actually in isn't documented (see
    // RuneScapeDatabaseInitializer's player_activity comment). Every row comes from the same API in
    // the same unknown-but-consistent zone, so a same-week relative ordering is safe even without
    // knowing what that zone is.
    private static final DateTimeFormatter ACTIVITY_DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH);
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

    /** Just the joins/leaves half — the Tracking panel's per-group "Send This Week's Report Now" button calls this or {@link #sendCitadelReport} individually rather than always sending both. */
    public void sendJoinsLeaves(Guild guild, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        long guildId = guild.getIdLong();
        List<WeeklyDigestRepository.RosterEvent> events = repository.getRosterEventsInWindow(guildId, windowStart, windowEnd);
        if (events.isEmpty()) return;

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
    }

    public void sendCitadelReport(Guild guild, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        List<CitadelEntry> entries = computeCitadelEntries(guild.getIdLong(), windowStart, windowEnd);
        if (entries.stream().noneMatch(e -> e.visited || e.capped)) return;

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Weekly Citadel Report — " + formatRange(windowStart, windowEnd)));

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
            LocalDateTime parsed = parseActivityDate(row.activityDate());
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

    private static LocalDateTime parseActivityDate(String raw) {
        try {
            return LocalDateTime.parse(raw, ACTIVITY_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return LocalDateTime.MIN;
        }
    }

    private static String formatRange(OffsetDateTime start, OffsetDateTime end) {
        DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);
        return start.toLocalDate().format(formatter) + " – " + end.toLocalDate().minusDays(1).format(formatter);
    }
}
