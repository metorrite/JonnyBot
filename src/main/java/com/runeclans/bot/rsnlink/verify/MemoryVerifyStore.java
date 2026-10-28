package com.runeclans.bot.rsnlink.verify;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** {@link VerifyStore} in memory: for tests, and for a service run without a database. Lost on restart. */
public final class MemoryVerifyStore implements VerifyStore {
    private final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private final Map<UUID, Watch> watches = new ConcurrentHashMap<>();

    @Override
    public synchronized void insertClaim(Claim claim) {
        claims.put(claim.id(), claim);
    }

    @Override
    public synchronized void updateClaim(Claim claim) {
        claims.put(claim.id(), claim);
    }

    @Override
    public synchronized List<Claim> openClaims(UUID linkId) {
        return claims.values().stream().filter(c -> c.linkId().equals(linkId) && c.status().open()).sorted(Comparator.comparing(Claim::claimedAt)).toList();
    }

    @Override
    public synchronized Map<ClaimStatus, Integer> claimCounts(UUID linkId) {
        Map<ClaimStatus, Integer> out = new EnumMap<>(ClaimStatus.class);
        for (Claim c : claims.values()) if (c.linkId().equals(linkId)) out.merge(c.status(), 1, Integer::sum);
        return out;
    }

    @Override
    public synchronized int claimsSince(UUID linkId, Instant since) {
        return (int) claims.values().stream().filter(c -> c.linkId().equals(linkId) && !c.claimedAt().isBefore(since)).count();
    }

    @Override
    public synchronized List<Claim> matchedClaims(UUID linkId) {
        return claims.values().stream().filter(c -> c.linkId().equals(linkId) && c.status() == ClaimStatus.MATCH)
                .sorted(Comparator.comparing(Claim::claimedAt)).toList();
    }

    @Override
    public Optional<Watch> watch(UUID linkId) {
        return Optional.ofNullable(watches.get(linkId));
    }

    @Override
    public void saveWatch(Watch watch) {
        watches.put(watch.linkId(), watch);
    }

    @Override
    public List<Watch> dueWatches(Instant now, int limit) {
        return watches.values().stream().filter(w -> w.corroboratedAt() == null && w.nextCheckAt() != null && !w.nextCheckAt().isAfter(now))
                .sorted(Comparator.comparing(Watch::nextCheckAt)).limit(limit).toList();
    }

    @Override
    public synchronized Map<String, Integer> totals(Instant now) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ClaimStatus s : ClaimStatus.values()) out.put("claims_" + s.name().toLowerCase(), 0);
        for (Claim c : claims.values()) out.merge("claims_" + c.status().name().toLowerCase(), 1, Integer::sum);
        out.put("watches", watches.size());
        out.put("watchesDue", dueWatches(now, Integer.MAX_VALUE).size());
        out.put("corroborated", (int) watches.values().stream().filter(w -> w.corroboratedAt() != null).count());
        return out;
    }

    @Override
    public List<Watch> recentlyCorroborated(int limit) {
        return watches.values().stream().filter(w -> w.corroboratedAt() != null).sorted(Comparator.comparing(Watch::corroboratedAt).reversed())
                .limit(limit).toList();
    }

    @Override
    public synchronized int sweep(Instant cutoff) {
        List<UUID> gone = new ArrayList<>();
        for (Claim c : claims.values()) if (c.settledAt() != null && c.settledAt().isBefore(cutoff)) gone.add(c.id());
        gone.forEach(claims::remove);
        int removed = gone.size();
        List<UUID> idle = new ArrayList<>();
        for (Watch w : watches.values()) if (w.corroboratedAt() == null && w.nextCheckAt() == null && w.lastReportAt().isBefore(cutoff)) idle.add(w.linkId());
        idle.forEach(watches::remove);
        return removed + idle.size();
    }
}
