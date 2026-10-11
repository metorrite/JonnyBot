package com.younglings.bot.clanprofile;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Clan-versus-clan leaderboards from data we already collect: the total XP a clan's active members gained over a period. Only clans that
 * chose to take part (and are publicly listed) are ranked, and only totals are shown, never a member's own figure.
 */
public final class ClanLeaderboard {
    public enum Period {
        WEEK("week", Duration.ofDays(7)), MONTH("month", Duration.ofDays(30));

        private final String key;
        private final Duration length;

        Period(String key, Duration length) {
            this.key = key;
            this.length = length;
        }

        public String key() {
            return key;
        }

        /** The start of this period, counting back from {@code now}. */
        public Instant since(Instant now) {
            return now.minus(length);
        }

        public static Optional<Period> parse(String text) {
            if (text == null) return Optional.empty();
            for (Period p : values()) if (p.key.equals(text.strip().toLowerCase(Locale.ROOT))) return Optional.of(p);
            return Optional.empty();
        }
    }

    /** One clan's total and what it is made of. {@code members} is how many active members were counted. */
    public record Entry(String slug, String name, long xpGained, int members) {
        /** Average gained per counted member, 0 when nobody was counted. */
        public long perMember() {
            return members <= 0 ? 0 : xpGained / members;
        }
    }

    private ClanLeaderboard() {}

    /** Biggest total first; ties are broken by name so the order never flickers between requests. */
    public static List<Entry> rank(List<Entry> entries) {
        return entries.stream().sorted(Comparator.comparingLong(Entry::xpGained).reversed().thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }
}
