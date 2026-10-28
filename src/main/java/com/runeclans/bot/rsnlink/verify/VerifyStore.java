package com.runeclans.bot.rsnlink.verify;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The port to wherever claims and watches are kept: {@link PgVerifyStore} in the link database, {@link MemoryVerifyStore} in tests. Only the
 * verifier's single worker thread calls it, so implementations need no locking beyond what their storage gives.
 */
public interface VerifyStore {

    /** What became of one reported XP value. */
    enum ClaimStatus {
        /** Reported; public data hasn't been read since. */
        UNCHECKED,
        /** Read public data after the report and the value wasn't public anywhere: a real prediction, waiting for the Hiscores. */
        PENDING,
        /** The value (to the whole XP) was already public when we looked, so it proves nothing. */
        REFUSED_PUBLIC,
        /** Public data couldn't be read in time, so it was never shown to be a prediction. */
        REFUSED_UNCHECKED,
        /** The Hiscores later showed exactly this value, and it had gained enough to count. */
        MATCH,
        /** The Hiscores later showed exactly this value, but the gain was too small or unknown to count. */
        MATCH_SMALL,
        /** The Hiscores moved past this value without ever showing it, and it was the latest claim for the skill. */
        MISS,
        /** A later claim for the same skill took over, or an earlier one matched. */
        SUPERSEDED,
        /** Still waiting when its time ran out (the player never ranked, or never logged out). */
        EXPIRED;

        public boolean open() {
            return this == UNCHECKED || this == PENDING;
        }
    }

    /**
     * One reported XP value.
     *
     * @param baselineXp the most whole XP any public source showed for the skill when it was checked, {@link Skills#UNRANKED} if none did
     */
    record Claim(UUID id, UUID linkId, int skill, long xp10, Instant claimedAt, ClaimStatus status, long baselineXp, Instant checkedAt,
                 Instant settledAt, Long publishedXp) {
        Claim checked(ClaimStatus to, long baseline, Instant at) {
            return new Claim(id, linkId, skill, xp10, claimedAt, to, baseline, at, to.open() ? null : at, publishedXp);
        }

        Claim settled(ClaimStatus to, Long published, Instant at) {
            return new Claim(id, linkId, skill, xp10, claimedAt, to, baselineXp, checkedAt, at, published);
        }
    }

    /**
     * What the verifier keeps per link between checks.
     *
     * @param rsn           the display name to read public data under (public anyway; follows renames)
     * @param lastState     the plugin's last game state: {@code game}, {@code lobby} or {@code logout}
     * @param attempt       how many checks since the last report, for the back-off
     * @param budgetDay     the UTC day {@code budgetUsed} counts for
     * @param ironman       null until probed; true when the player is on the ironman Hiscores too
     */
    record Watch(UUID linkId, String rsn, String lastState, Instant lastReportAt, Instant nextCheckAt, int attempt, LocalDate budgetDay,
                 int budgetUsed, Boolean ironman, Instant ironmanCheckedAt, int matches, int misses, Instant lastPublishedAt,
                 Instant corroboratedAt, Instant createdAt) {
        static Watch fresh(UUID linkId, String rsn, Instant now) {
            return new Watch(linkId, rsn, "game", now, null, 0, null, 0, null, null, 0, 0, null, null, now);
        }

        Watch reported(String name, String state, Instant now) {
            return new Watch(linkId, name, state, now, now, 0, budgetDay, budgetUsed, ironman, ironmanCheckedAt, matches, misses, lastPublishedAt,
                    corroboratedAt, createdAt);
        }

        Watch scheduled(Instant next, int nextAttempt) {
            return new Watch(linkId, rsn, lastState, lastReportAt, next, nextAttempt, budgetDay, budgetUsed, ironman, ironmanCheckedAt, matches, misses,
                    lastPublishedAt, corroboratedAt, createdAt);
        }

        Watch spent(LocalDate day, int used) {
            return new Watch(linkId, rsn, lastState, lastReportAt, nextCheckAt, attempt, day, used, ironman, ironmanCheckedAt, matches, misses,
                    lastPublishedAt, corroboratedAt, createdAt);
        }

        Watch probed(Boolean onIronman, Instant at) {
            return new Watch(linkId, rsn, lastState, lastReportAt, nextCheckAt, attempt, budgetDay, budgetUsed, onIronman, at, matches, misses,
                    lastPublishedAt, corroboratedAt, createdAt);
        }

        Watch counted(int m, int x, Instant published) {
            return new Watch(linkId, rsn, lastState, lastReportAt, nextCheckAt, attempt, budgetDay, budgetUsed, ironman, ironmanCheckedAt, m, x,
                    published, corroboratedAt, createdAt);
        }

        Watch corroborated(Instant at) {
            return new Watch(linkId, rsn, lastState, lastReportAt, null, attempt, budgetDay, budgetUsed, ironman, ironmanCheckedAt, matches, misses,
                    lastPublishedAt, at, createdAt);
        }
    }

    void insertClaim(Claim claim);

    void updateClaim(Claim claim);

    /** The link's claims that are still open ({@link ClaimStatus#UNCHECKED} or {@link ClaimStatus#PENDING}), oldest first. */
    List<Claim> openClaims(UUID linkId);

    /** How many of the link's claims are in each status (all kept history). */
    Map<ClaimStatus, Integer> claimCounts(UUID linkId);

    /** How many claims the link has made since {@code since}, for the per-link report limit. */
    int claimsSince(UUID linkId, Instant since);

    Optional<Watch> watch(UUID linkId);

    void saveWatch(Watch watch);

    /** Watches whose next check is due, soonest first. */
    List<Watch> dueWatches(Instant now, int limit);

    /** Claim counts by status across every link, and watch counts, for the owner's overview. */
    Map<String, Integer> totals(Instant now);

    /** The link's claims that matched, oldest first (for the evidence: skill names only). */
    List<Claim> matchedClaims(UUID linkId);

    /** Recently corroborated links, newest first. */
    List<Watch> recentlyCorroborated(int limit);

    /** Deletes settled claims settled before {@code cutoff}, and idle watches last reported before it. Returns rows removed. */
    int sweep(Instant cutoff);
}
