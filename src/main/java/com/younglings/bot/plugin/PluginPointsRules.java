package com.younglings.bot.plugin;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.List;
import java.util.Locale;

/**
 * A clan's points for what the plugin reports (T-061), one rule per event type, all off until staff set them on the dashboard's Clan plugin
 * page. Pure, so every rule can be tested.
 * <ul>
 *   <li>{@code points}: paid for each report of that type (0 = off).</li>
 *   <li>{@code dailyCap}: at most this many points from that type per member per day, UTC (0 = no cap).</li>
 *   <li>{@code minValueGp}: drops only, the smallest drop worth paying for (JonnyBot's own Grand Exchange price; an unpriced drop never pays).</li>
 * </ul>
 * XP has its own rule ({@link XpRule}, T-542): an XP report is a running total, not something that happened once, so XP is paid per UTC day from the
 * RuneClans hub's XP ledger instead ({@link PluginXpPoints}). Presence has no rule.
 */
public final class PluginPointsRules {
    public static final List<PluginEventType> TYPES = List.of(PluginEventType.KILL, PluginEventType.DROP, PluginEventType.CLUE, PluginEventType.LEVEL_UP,
            PluginEventType.QUEST_COMPLETE, PluginEventType.ACHIEVEMENT, PluginEventType.COLLECTION_LOG);
    public static final int MAX_POINTS = 10_000;
    public static final int MAX_DAILY_CAP = 1_000_000;
    public static final long DEFAULT_MIN_VALUE_GP = 1_000_000;
    public static final long DEFAULT_PER_XP = 100_000;
    public static final long MIN_PER_XP = 1_000;
    /** A skill's whole XP. */
    public static final long MAX_PER_XP = 200_000_000;

    public record Rule(PluginEventType type, int points, int dailyCap, Long minValueGp) {
        public boolean on() {
            return points > 0;
        }
    }

    /**
     * The XP rule (T-542): {@code points} for every {@code perXp} XP a member gained in one UTC day, at most {@code dailyCap} a day (0 = no cap), paid
     * once per member per day after the day ends. Off until staff switch it on; switched off, its numbers are kept.
     */
    public record XpRule(boolean enabled, int points, long perXp, int dailyCap) {
        public static final XpRule OFF = new XpRule(false, 0, DEFAULT_PER_XP, 0);

        public boolean on() {
            return enabled && points > 0 && perXp > 0;
        }
    }

    private PluginPointsRules() {}

    /** What the dashboard calls each type. */
    public static String label(PluginEventType type) {
        return switch (type) {
            case KILL -> "Boss kill";
            case DROP -> "Valuable drop";
            case CLUE -> "Clue scroll";
            case LEVEL_UP -> "Level up";
            case QUEST_COMPLETE -> "Quest";
            case ACHIEVEMENT -> "Achievement";
            case COLLECTION_LOG -> "Collection log item";
            default -> type.name().toLowerCase(Locale.ROOT);
        };
    }

    /** What a problem with one rule is, or null when it is fine. */
    public static String problem(Rule rule) {
        if (!TYPES.contains(rule.type())) return "Points can't be set for " + rule.type().name() + ".";
        if (rule.points() < 0 || rule.points() > MAX_POINTS) return label(rule.type()) + ": points must be 0 to " + MAX_POINTS + ".";
        if (rule.dailyCap() < 0 || rule.dailyCap() > MAX_DAILY_CAP) return label(rule.type()) + ": the daily cap must be 0 (none) to " + MAX_DAILY_CAP + ".";
        if (rule.minValueGp() != null && rule.minValueGp() < 0) return label(rule.type()) + ": the smallest value can't be negative.";
        return null;
    }

    /** What a problem with the XP rule is, or null when it is fine. */
    public static String xpProblem(XpRule rule) {
        if (rule.points() < 0 || rule.points() > MAX_POINTS) return "XP gained: points must be 0 to " + MAX_POINTS + ".";
        if (rule.perXp() < MIN_PER_XP || rule.perXp() > MAX_PER_XP) return "XP gained: the XP for each step must be " + MIN_PER_XP + " to " + MAX_PER_XP + ".";
        if (rule.dailyCap() < 0 || rule.dailyCap() > MAX_DAILY_CAP) return "XP gained: the daily cap must be 0 (none) to " + MAX_DAILY_CAP + ".";
        if (rule.enabled() && rule.points() == 0) return "XP gained: set the points before switching it on.";
        return null;
    }

    /** What one UTC day's XP earns: whole steps of {@code perXp} times the points, held to the daily cap (never more than one ledger entry may hold). */
    public static int xpPoints(XpRule rule, long xp) {
        if (!rule.on() || xp < rule.perXp()) return 0;
        long earned = (xp / rule.perXp()) * rule.points();
        long cap = rule.dailyCap() > 0 ? Math.min(rule.dailyCap(), MAX_DAILY_CAP) : MAX_DAILY_CAP;
        return (int) Math.min(earned, cap);
    }

    /** The reason written in the points ledger for a day's XP: "RuneSync: 1.2M XP gained". */
    public static String xpReason(long xp) {
        return "RuneSync: " + compactXp(xp) + " XP gained";
    }

    /** 950, 9,500, 12.3k, 1.2M: rounded down, so it never claims more than was gained. */
    static String compactXp(long xp) {
        if (xp >= 1_000_000) return tenths(xp / 100_000) + "M";
        if (xp >= 10_000) return tenths(xp / 100) + "k";
        return String.format(Locale.ROOT, "%,d", xp);
    }

    private static String tenths(long t) {
        return String.format(Locale.ROOT, "%,d", t / 10) + (t % 10 == 0 ? "" : "." + t % 10);
    }

    /** Whether this report earns the rule's points at all (before the daily cap): drops must be priced at or above the smallest value. */
    public static boolean pays(Rule rule, DataObject data) {
        if (!rule.on()) return false;
        if (rule.type() != PluginEventType.DROP) return true;
        if (!data.hasKey("valueGp") || data.isNull("valueGp") || !(data.opt("valueGp").orElse(null) instanceof Number value)) return false;
        long min = rule.minValueGp() == null ? DEFAULT_MIN_VALUE_GP : rule.minValueGp();
        return value.longValue() >= min; // valueGp is already quantity times price (PluginEnrichment)
    }

    /** How many of the rule's points still fit under the daily cap, given what was paid today: all, or nothing (never part of an award). */
    public static int afterCap(Rule rule, long paidToday) {
        if (rule.dailyCap() <= 0) return rule.points();
        return paidToday + rule.points() <= rule.dailyCap() ? rule.points() : 0;
    }

    /** The reason written in the points ledger: "RuneSync: boss kill (Telos)". */
    public static String reason(PluginEventType type, DataObject data) {
        String what = switch (type) {
            case KILL -> text(data, "bossName");
            case DROP, COLLECTION_LOG -> text(data, "itemName");
            case CLUE -> text(data, "tier").isEmpty() ? "" : text(data, "tier") + " clue";
            case LEVEL_UP -> text(data, "skill") + (data.opt("level").orElse(null) instanceof Number n ? " " + n.longValue() : "");
            case QUEST_COMPLETE -> text(data, "quest");
            case ACHIEVEMENT -> text(data, "name");
            default -> "";
        };
        String reason = "RuneSync: " + label(type).toLowerCase(Locale.ROOT) + (what.isBlank() ? "" : " (" + what.strip() + ")");
        return reason.length() > 190 ? reason.substring(0, 190) : reason;
    }

    private static String text(DataObject data, String key) {
        return data.hasKey(key) && !data.isNull(key) && data.opt(key).orElse(null) instanceof String s ? s : "";
    }
}
