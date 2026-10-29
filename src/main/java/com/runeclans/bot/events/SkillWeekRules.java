package com.runeclans.bot.events;

import com.runeclans.bot.runescape.RuneScapeSkillCatalog;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Skill of the week (T-541), the rules without a database: the week (Monday 00:00 UTC to Sunday 23:59:59 UTC), which skill of the server's rota
 * comes next, and who won from the XP each player gained.
 */
public final class SkillWeekRules {
    private SkillWeekRules() {}

    /** The most skills a rota holds. */
    public static final int MAX_ROTA = 30;
    /** How many winners a closed week keeps. */
    public static final int WINNERS = 3;
    /** How long after the week ends it is closed: RuneSync's gains reach the hub's ledger a minute or two late. */
    public static final Duration CLOSE_AFTER = Duration.ofMinutes(10);
    /** How long after the week ends it is closed anyway when the hub still can't be asked for everyone. */
    public static final Duration GIVE_UP_AFTER = Duration.ofHours(24);
    /**
     * The XP objective's target: 1M XP (the most it can be), so its {@link EventRules#MAX_REPEAT} completions hold 1B XP, above any week's gain in one
     * skill (200M cap). The competition's points are the XP itself, not completions ({@link CompetitionTemplate#standings} for {@code wholeSpan}).
     */
    public static final long XP_PER_POINT = 1_000_000;

    public record Winner(int rank, long userId, long xp) {}

    /** Monday 00:00 UTC of the week {@code at} falls in. */
    public static Instant weekStart(Instant at) {
        return at.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Sunday 23:59:59 UTC of the week that starts at {@code start}. */
    public static Instant weekEnd(Instant start) {
        return start.plus(7, ChronoUnit.DAYS).minusSeconds(1);
    }

    /** Whether a week that ends at {@code end} can be closed at {@code now}. */
    public static boolean closeDue(Instant end, Instant now) {
        return !now.isBefore(end.plus(CLOSE_AFTER));
    }

    /** Whether a week that ends at {@code end} and still can't be brought up to date is closed anyway at {@code now}. */
    public static boolean giveUp(Instant end, Instant now) {
        return !now.isBefore(end.plus(GIVE_UP_AFTER));
    }

    /** The skill at {@code position} of the rota, going round; -1 for an empty rota. */
    public static int skillAt(List<Integer> rota, int position) {
        if (rota.isEmpty()) return -1;
        return rota.get(Math.floorMod(position, rota.size()));
    }

    /** Where a new rota goes on from: the skill after {@code lastSkill} (the latest week's) when the rota has it, else its first skill. */
    public static int positionAfter(List<Integer> rota, Integer lastSkill) {
        int at = lastSkill == null ? -1 : rota.indexOf(lastSkill);
        return at < 0 ? 0 : at + 1;
    }

    /**
     * Problems with a rota as an admin sent it: skills from the catalogue (no "overall"), each once (so {@link #positionAfter} always knows where
     * this week's skill is), at least one when switched on, at most {@link #MAX_ROTA}.
     */
    public static List<String> rotaProblems(List<Integer> rota, boolean enabled) {
        List<String> problems = new ArrayList<>();
        if (enabled && rota.isEmpty()) problems.add("Choose at least one skill for the rota.");
        if (rota.size() > MAX_ROTA) problems.add("A rota holds at most " + MAX_ROTA + " skills.");
        for (Integer id : rota) {
            if (id == null || id < 0 || id >= RuneScapeSkillCatalog.skillCount()) {
                problems.add("Choose each skill from the list.");
                break;
            }
        }
        if (new java.util.HashSet<>(rota).size() < rota.size()) problems.add("Each skill can be in the rota once.");
        return problems;
    }

    /**
     * The winners from each player's XP gained in the week: most XP first, at most {@code places} ranks, nobody who gained nothing. Equal XP shares a
     * rank (1, 1, 3) and the earlier id is listed first, so the order never depends on the database's.
     */
    public static List<Winner> winners(Map<Long, Long> xpByUser, int places) {
        List<Map.Entry<Long, Long>> sorted = new ArrayList<>(xpByUser.entrySet().stream().filter(e -> e.getValue() != null && e.getValue() > 0).toList());
        sorted.sort(Comparator.<Map.Entry<Long, Long>>comparingLong(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey));
        List<Winner> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            int rank = i > 0 && sorted.get(i).getValue().equals(sorted.get(i - 1).getValue()) ? out.get(i - 1).rank() : i + 1;
            if (rank > places) break;
            out.add(new Winner(rank, sorted.get(i).getKey(), sorted.get(i).getValue()));
        }
        return out;
    }

    /**
     * The skill members voted for (T-711): the rota is the ballot, so votes for a skill not in it don't count. Most votes wins; a tie goes to the
     * tied skill that comes first going round the rota from {@code position} (the rota's next in line), and with no votes the rota's next wins.
     * -1 for an empty rota.
     */
    public static int voteWinner(List<Integer> rota, int position, Map<Integer, Integer> votes) {
        if (rota.isEmpty()) return -1;
        int best = skillAt(rota, position);
        int most = 0;
        for (int i = 0; i < rota.size(); i++) {
            int skill = skillAt(rota, position + i);
            int count = votes.getOrDefault(skill, 0);
            if (count > most) {
                best = skill;
                most = count;
            }
        }
        return best;
    }

    /** The start of the week members vote for while the week that {@code at} falls in runs: the next one. */
    public static Instant votingFor(Instant at) {
        return weekStart(at).plus(7, ChronoUnit.DAYS);
    }

    /** "Skill of the week: Fletching (26 Oct)". */
    public static String eventName(int skillId, Instant start) {
        return "Skill of the week: " + CompetitionTemplate.skillName(skillId) + " (" + DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(ZoneOffset.UTC).format(start) + ")";
    }
}
