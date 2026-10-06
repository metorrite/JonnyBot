package com.younglings.bot.internal;

import com.younglings.bot.tracking.BossCatalog;
import com.younglings.bot.tracking.DropItemCatalog;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sorts a RuneMetrics adventure-log line into a kind the website can icon and filter on, and picks the
 * boss/kill count or drop out of the lines that carry one. Pure text matching over the same phrasing the
 * tracking feed already recognises ({@link com.younglings.bot.tracking.TrackingEventClassifier}); anything it
 * doesn't recognise is {@link Kind#OTHER}, which the site's feed simply leaves out.
 */
final class ActivityKinds {
    private ActivityKinds() {}

    enum Kind { LEVEL_UP, XP_MILESTONE, QUEST, BOSS, DROP, PET, CITADEL_CAP, CITADEL_VISIT, CLUE, CHALLENGE, OTHER }

    private static final Pattern LEVEL_UP = Pattern.compile("^Levelled up (.+?)\\.?$");
    private static final Pattern XP_MILESTONE = Pattern.compile("^([\\d,]+)XP in (.+)$");
    private static final Pattern QUEST = Pattern.compile("^Quest complete: ?(.+)$");
    private static final Pattern PET = Pattern.compile("^I found (.+?), the (.+?) pet\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHALLENGE = Pattern.compile("Won a challenge against the (.+?) Champion", Pattern.CASE_INSENSITIVE);
    private static final Pattern KILLED = Pattern.compile("^I (?:killed|defeated) (\\d[\\d,]*)? ?(.+?)\\.?$");

    /** Longest boss names first, so "Kalphite King" is never mistaken for a shorter name it contains. */
    private static final List<BossCatalog.Boss> BOSSES_BY_LENGTH = BossCatalog.all().stream()
            .sorted(Comparator.comparingInt((BossCatalog.Boss b) -> b.name().length()).reversed()).toList();

    static Kind kindOf(String text) {
        if (text.startsWith("Capped at my Clan Citadel")) return Kind.CITADEL_CAP;
        if (text.startsWith("Visited my Clan Citadel")) return Kind.CITADEL_VISIT;
        if (QUEST.matcher(text).matches()) return Kind.QUEST;
        if (LEVEL_UP.matcher(text).matches()) return Kind.LEVEL_UP;
        if (XP_MILESTONE.matcher(text).matches()) return Kind.XP_MILESTONE;
        if (CHALLENGE.matcher(text).find()) return Kind.CHALLENGE;
        if (PET.matcher(text).matches() || text.contains("I adopted TzRek-Jad") || text.toLowerCase().contains("effigy pet")) return Kind.PET;
        if (text.toLowerCase().contains("treasure trail completed")) return Kind.CLUE;
        if (bossOf(text).isPresent()) return Kind.BOSS;
        if (dropOf(text).isPresent()) return Kind.DROP;
        return Kind.OTHER;
    }

    /**
     * The boss a kill line is about, if it is one. The tracking catalogue's names win where they match; for
     * any other boss (the catalogue is short) the name is read straight off the line — article dropped, cut at
     * a comma ("Amascut, the Devourer" → "Amascut"), and un-pluralised when the line gives a count.
     */
    static Optional<String> bossOf(String text) {
        if (!(text.startsWith("I killed") || text.startsWith("I defeated"))) return Optional.empty();
        for (BossCatalog.Boss boss : BOSSES_BY_LENGTH) {
            if (text.contains(boss.name())) return Optional.of(boss.name());
        }

        Matcher m = KILLED.matcher(text);
        if (!m.matches()) return Optional.empty();
        String name = m.group(2).trim().replaceFirst("^(?:an?|the) ", "");
        int comma = name.indexOf(',');
        if (comma > 0) name = name.substring(0, comma).trim();
        if (m.group(1) != null && killCount(text) != 1) name = singular(name);
        return name.isBlank() ? Optional.empty() : Optional.of(name);
    }

    /** "Amascuts" → "Amascut", "Nexes" → "Nex"; names that end like a singular ("Elidinis", "Araxxi") are left alone. */
    static String singular(String name) {
        if (name.endsWith("ies")) return name.substring(0, name.length() - 3) + "y";
        if (name.endsWith("xes")) return name.substring(0, name.length() - 2);
        if (name.endsWith("s") && !name.endsWith("ss") && !name.endsWith("us") && !name.endsWith("is")) return name.substring(0, name.length() - 1);
        return name;
    }

    /** How many kills the line reports — a bare "I killed Vorago." is one. */
    static int killCount(String text) {
        Matcher m = KILLED.matcher(text);
        if (m.matches() && m.group(1) != null) {
            try {
                return Integer.parseInt(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                // fall through to 1
            }
        }
        return 1;
    }

    /** The catalogued drop a "I found …" line names, if it is one. */
    static Optional<String> dropOf(String text) {
        if (!text.startsWith("I found")) return Optional.empty();
        if (PET.matcher(text).matches()) return Optional.empty();
        return DropItemCatalog.findIn(text).map(DropItemCatalog.DropItem::name);
    }
}
