package com.younglings.bot.rsnlink.verify;

import com.younglings.bot.rsnlink.verify.PublicData.Board;
import com.younglings.bot.rsnlink.verify.VerifyStore.Claim;
import com.younglings.bot.rsnlink.verify.VerifyStore.ClaimStatus;
import com.younglings.bot.rsnlink.verify.VerifyStore.Watch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "The plugin knew it first" (T-170, plugin-auth research option 2). RS3 Hiscores only change when a player logs out or goes to the lobby,
 * so a plugin that reports an exact XP value <em>before</em> any public source shows it, which the Hiscores then show exactly after logout,
 * was watching that character's game live. A script on someone else's PC can only copy values once they are public.
 * <p>
 * How a link gets corroborated:
 * <ol>
 *   <li>The plugin reports XP in tenths, per skill ({@link #report}). Each value is a claim, {@link ClaimStatus#UNCHECKED}.</li>
 *   <li>The worker reads public data <em>after</em> the report: Hiscores lite (and the ironman table when the main one doesn't rank the skill)
 *       and RuneMetrics. A claim whose whole XP is already shown anywhere is {@link ClaimStatus#REFUSED_PUBLIC}; otherwise it is
 *       {@link ClaimStatus#PENDING}, a real prediction, with the public value seen as its baseline. Public values only go up, so any read made
 *       after the report that shows less proves the value wasn't public when it was reported.</li>
 *   <li>After the player logs out (the plugin says so, or reports stop) the worker reads the Hiscores again, backing off. When a skill's
 *       public value changes: a pending claim equal to it is a {@link ClaimStatus#MATCH} (if it gained at least {@code minGainXp} on its
 *       baseline); the latest claim for that skill that the public value has moved past is a {@link ClaimStatus#MISS}.</li>
 *   <li>With at least {@code minMatches} matches and no more than {@link #MISSES_PER_MATCH} misses per match, the link is corroborated and
 *       reported to the {@link Sink}.</li>
 * </ol>
 * Only the Hiscores can complete a match. RuneMetrics is used only to refuse values that are already public, so the check stays safe even if
 * RuneMetrics turns out to update during play (experiment E2). All calls happen on one worker thread; nothing here is called while a plugin
 * request waits.
 */
public final class XpVerifier {
    private static final Logger log = LoggerFactory.getLogger(XpVerifier.class);

    /** Wait this long after the last report before the first after-logout read: Hiscores update a little after the lobby. */
    static final Duration AFTER_LOBBY = Duration.ofMinutes(2);
    /** Without a lobby/logout report, the player is presumed gone once reports stop for this long. */
    static final Duration AFTER_QUIET = Duration.ofMinutes(15);
    /** Back-off after the first after-logout read, measured from the last report. */
    static final List<Duration> BACK_OFF = List.of(Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(2),
            Duration.ofHours(4), Duration.ofHours(8), Duration.ofHours(16), Duration.ofHours(24), Duration.ofHours(36), Duration.ofHours(48),
            Duration.ofHours(72));
    /** After a failed read. */
    static final Duration RETRY = Duration.ofMinutes(5);
    /** After a login (the session before it has ended, so the Hiscores may have just updated). */
    static final Duration AFTER_LOGIN = Duration.ofSeconds(30);

    /** Told when a link is corroborated. Called on the worker thread. */
    public interface Sink {
        void corroborated(UUID linkId, String evidenceJson);
    }

    /** What the plugin sent: skill id to XP in tenths, and the game state it was in. */
    public record Report(UUID linkId, String rsn, String state, Map<Integer, Long> xp10) {}

    /** The answer to a report, sent back to the plugin. */
    public record Accepted(int accepted, int ignored) {}

    private final VerifySettings settings;
    private final VerifyStore store;
    private final PublicData data;
    private final Sink sink;
    private final Clock clock;
    private final Deque<Long> globalReads = new ArrayDeque<>();
    private final Map<String, CachedView> cache = new HashMap<>();
    private int budgetRefusals;
    private int failedReads;

    public XpVerifier(VerifySettings settings, VerifyStore store, PublicData data, Sink sink, Clock clock) {
        this.settings = settings;
        this.store = store;
        this.data = data;
        this.sink = sink;
        this.clock = clock;
    }

    public VerifySettings settings() {
        return settings;
    }

    // =====================================================================================================================
    // Input
    // =====================================================================================================================

    /** Records a report's values as claims and asks for a check now. Values that can't be predictions are counted as ignored. */
    public Accepted report(Report r) {
        if (!settings.enabled()) return new Accepted(0, r.xp10().size());
        Instant now = clock.instant();
        Watch w = store.watch(r.linkId()).orElse(null);
        if (w != null && w.corroboratedAt() != null) return new Accepted(0, r.xp10().size());
        int room = VerifySettings.CLAIMS_PER_LINK_HOUR - store.claimsSince(r.linkId(), now.minus(Duration.ofHours(1)));
        int accepted = 0;
        int ignored = 0;
        for (Map.Entry<Integer, Long> e : r.xp10().entrySet()) {
            Integer skill = e.getKey();
            Long xp10 = e.getValue();
            // A capped skill never changes again, so its value is public for good; zero or less is nonsense.
            if (skill == null || xp10 == null || !Skills.valid(skill) || xp10 <= 0 || xp10 >= Skills.MAX_XP10 || accepted >= room) {
                ignored++;
                continue;
            }
            store.insertClaim(new Claim(UUID.randomUUID(), r.linkId(), skill, xp10, now, ClaimStatus.UNCHECKED, Skills.UNRANKED, null, null, null));
            accepted++;
        }
        String state = normaliseState(r.state());
        Watch base = w == null ? Watch.fresh(r.linkId(), r.rsn(), now) : w;
        // A report always asks for a check now (to show its values aren't public yet) and restarts the back-off.
        if (accepted > 0 || !state.equals("game")) store.saveWatch(base.reported(r.rsn(), state, now));
        return new Accepted(accepted, ignored);
    }

    /** The plugin logged in again: the session before has ended, so read the Hiscores soon if anything is waiting. */
    public void loggedIn(UUID linkId) {
        if (!settings.enabled()) return;
        Instant now = clock.instant();
        store.watch(linkId).ifPresent(w -> {
            if (w.corroboratedAt() != null || store.openClaims(linkId).isEmpty()) return;
            Instant soon = now.plus(AFTER_LOGIN);
            if (w.nextCheckAt() == null || w.nextCheckAt().isAfter(soon)) store.saveWatch(w.scheduled(soon, w.attempt()));
        });
    }

    // =====================================================================================================================
    // The worker
    // =====================================================================================================================

    /** Checks every watch that is due, as far as the service-wide rate allows. Returns how many were checked. */
    public int runDue() {
        if (!settings.enabled()) return 0;
        Instant now = clock.instant();
        int done = 0;
        for (Watch w : store.dueWatches(now, 50)) {
            if (!globalAllows(now)) break;
            try {
                check(w, now);
            } catch (RuntimeException e) {
                log.warn("Link verification check failed for link {}: {}", w.linkId(), e.getClass().getSimpleName());
                store.saveWatch(w.scheduled(now.plus(RETRY), w.attempt()));
            }
            done++;
        }
        return done;
    }

    /** One check of one link: read public data, settle what it settles, schedule the next. */
    void check(Watch w, Instant now) {
        List<Claim> open = store.openClaims(w.linkId());
        if (open.isEmpty()) {
            store.saveWatch(w.scheduled(null, 0));
            return;
        }
        expire(open, now);
        open = store.openClaims(w.linkId());
        if (open.isEmpty()) {
            store.saveWatch(w.scheduled(null, 0));
            return;
        }

        // Per-link daily budget, counted on the watch.
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        int used = today.equals(w.budgetDay()) ? w.budgetUsed() : 0;
        if (used >= settings.checksPerLinkPerDay()) {
            budgetRefusals++;
            store.saveWatch(w.spent(today, used).scheduled(today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC), w.attempt()));
            return;
        }

        Instant newestClaim = open.stream().map(Claim::claimedAt).max(Instant::compareTo).orElse(now);
        Set<Integer> skills = new LinkedHashSet<>();
        for (Claim c : open) skills.add(c.skill());
        View view = read(w, newestClaim, skills, now);
        w = store.watch(w.linkId()).orElse(w); // read() may have recorded an ironman probe
        w = w.spent(today, used + (view.fresh ? 1 : 0));
        if (!view.hiscoresOk) {
            failedReads++;
            store.saveWatch(w.scheduled(now.plus(RETRY), w.attempt()));
            return;
        }

        Instant published = settle(w.linkId(), open, view, now);
        refuseOrAccept(store.openClaims(w.linkId()), view, now);

        Map<ClaimStatus, Integer> counts = store.claimCounts(w.linkId());
        int matches = counts.getOrDefault(ClaimStatus.MATCH, 0);
        int misses = counts.getOrDefault(ClaimStatus.MISS, 0);
        w = w.counted(matches, misses, published != null ? published : w.lastPublishedAt());
        if (corroborates(matches, misses)) {
            w = w.corroborated(now);
            store.saveWatch(w);
            log.info("Link {} corroborated by public data ({} matches, {} misses)", w.linkId(), matches, misses);
            sink.corroborated(w.linkId(), evidence(w, matches, misses));
            return;
        }
        store.saveWatch(schedule(w, store.openClaims(w.linkId()), now));
    }

    /**
     * Enough matches, and not too many misses. A real owner misses now and then (the client was closed before the plugin sent its last
     * batch), so a miss is forgiven up to {@link #MISSES_PER_MATCH} per match. Someone guessing needs thousands of misses per lucky match.
     */
    boolean corroborates(int matches, int misses) {
        return matches >= settings.minMatches() && misses <= matches * MISSES_PER_MATCH;
    }

    static final int MISSES_PER_MATCH = 2;

    /** Pending claims against the public values just read. Returns when a public value was seen to change, else null. */
    private Instant settle(UUID linkId, List<Claim> open, View view, Instant now) {
        Instant changed = null;
        Map<Integer, Claim> latestPending = new HashMap<>();
        for (Claim c : open) {
            if (c.status() == ClaimStatus.PENDING) latestPending.merge(c.skill(), c, (a, b) -> b.claimedAt().isAfter(a.claimedAt()) ? b : a);
        }
        Map<Integer, Claim> matchedAt = new HashMap<>();
        for (Claim c : open) {
            if (c.status() != ClaimStatus.PENDING) continue;
            long shown = view.hiscores[c.skill()];
            if (shown == Skills.UNRANKED || shown <= c.baselineXp()) continue; // nothing new published for this skill yet
            changed = now;
            long whole = Skills.wholeXp(c.xp10());
            if (shown == whole) {
                boolean counts = c.baselineXp() != Skills.UNRANKED && whole - c.baselineXp() >= settings.minGainXp();
                store.updateClaim(c.settled(counts ? ClaimStatus.MATCH : ClaimStatus.MATCH_SMALL, shown, now));
                matchedAt.put(c.skill(), c);
            } else if (shown > whole) {
                // Public data went past this value. Only the latest claim for the skill was meant to be the final one.
                boolean latest = latestPending.get(c.skill()) == c;
                store.updateClaim(c.settled(latest ? ClaimStatus.MISS : ClaimStatus.SUPERSEDED, shown, now));
            }
            // shown < whole: an earlier session's value was published; this claim's session hasn't been yet. Keep waiting.
        }
        // Anything older than a match for the same skill is superseded by it.
        for (Claim c : store.openClaims(linkId)) {
            Claim m = matchedAt.get(c.skill());
            if (c.status() == ClaimStatus.PENDING && m != null && c.claimedAt().isBefore(m.claimedAt())) {
                store.updateClaim(c.settled(ClaimStatus.SUPERSEDED, view.hiscores[c.skill()], now));
            }
        }
        return changed;
    }

    /** Unchecked claims made before this read: refused if already public anywhere, otherwise pending with the public value as baseline. */
    private void refuseOrAccept(List<Claim> open, View view, Instant now) {
        for (Claim c : open) {
            if (c.status() != ClaimStatus.UNCHECKED || !c.claimedAt().isBefore(view.readAt.plusNanos(1))) continue;
            long whole = Skills.wholeXp(c.xp10());
            long hs = view.hiscores[c.skill()];
            long rm = view.runeMetrics == null ? Skills.UNRANKED : Skills.wholeXp(view.runeMetrics[c.skill()]);
            if (hs >= whole || rm >= whole) {
                store.updateClaim(c.checked(ClaimStatus.REFUSED_PUBLIC, Math.max(hs, rm), now));
            } else {
                store.updateClaim(c.checked(ClaimStatus.PENDING, Math.max(hs, rm), now));
            }
        }
    }

    private void expire(List<Claim> open, Instant now) {
        for (Claim c : open) {
            if (c.status() == ClaimStatus.UNCHECKED && c.claimedAt().plus(VerifySettings.UNCHECKED_LIFETIME).isBefore(now)) {
                store.updateClaim(c.settled(ClaimStatus.REFUSED_UNCHECKED, null, now));
            } else if (c.status() == ClaimStatus.PENDING && c.claimedAt().plus(VerifySettings.CLAIM_LIFETIME).isBefore(now)) {
                store.updateClaim(c.settled(ClaimStatus.EXPIRED, null, now));
            }
        }
    }

    /** When to look next. */
    Watch schedule(Watch w, List<Claim> open, Instant now) {
        if (open.isEmpty()) return w.scheduled(null, 0);
        Instant earliest = now.plus(Duration.ofMinutes(1));
        if (open.stream().anyMatch(c -> c.status() == ClaimStatus.UNCHECKED)) return w.scheduled(earliest, w.attempt());
        Instant from = w.lastReportAt() == null ? now : w.lastReportAt();
        Duration first = w.lastState().equals("game") ? AFTER_QUIET : AFTER_LOBBY;
        int attempt = w.attempt();
        Duration wait = attempt == 0 ? first : BACK_OFF.get(Math.min(attempt - 1, BACK_OFF.size() - 1));
        Instant next = from.plus(wait);
        if (attempt > BACK_OFF.size()) next = from.plus(BACK_OFF.getLast()).plus(Duration.ofHours(24L * (attempt - BACK_OFF.size())));
        if (next.isBefore(earliest)) next = earliest;
        return w.scheduled(next, attempt + 1);
    }

    // =====================================================================================================================
    // Reading public data
    // =====================================================================================================================

    /** The public values for a name, read no earlier than {@code notBefore}. */
    private static final class View {
        final long[] hiscores = new long[Skills.COUNT];
        long[] runeMetrics; // null: private, missing, or failed
        boolean hiscoresOk;
        boolean fresh;
        Instant readAt;
    }

    private record CachedView(View view, boolean withIronman) {}

    private View read(Watch w, Instant notBefore, Set<Integer> skills, Instant now) {
        String key = w.rsn().toLowerCase(Locale.ROOT);
        boolean ironmanKnown = Boolean.TRUE.equals(w.ironman());
        CachedView cached = cache.get(key);
        if (cached != null && cached.view.hiscoresOk && cached.view.readAt.isAfter(notBefore)
                && cached.view.readAt.plus(VerifySettings.CACHE).isAfter(now) && (cached.withIronman || !ironmanKnown)) {
            View copy = cached.view;
            View v = new View();
            System.arraycopy(copy.hiscores, 0, v.hiscores, 0, Skills.COUNT);
            v.runeMetrics = copy.runeMetrics;
            v.hiscoresOk = true;
            v.readAt = copy.readAt;
            return v;
        }
        View v = new View();
        v.fresh = true;
        java.util.Arrays.fill(v.hiscores, Skills.UNRANKED);
        noteGlobalCheck(now);
        switch (data.hiscores(Board.MAIN, w.rsn())) {
            case PublicData.Hiscores.Found f -> {
                merge(v.hiscores, f.xp());
                v.hiscoresOk = true;
            }
            case PublicData.Hiscores.NotListed n -> v.hiscoresOk = true;
            case PublicData.Hiscores.Failed f -> v.hiscoresOk = false;
        }
        if (!v.hiscoresOk) return v;
        boolean needIronman = skills.stream().anyMatch(s -> v.hiscores[s] == Skills.UNRANKED);
        boolean probe = w.ironman() == null || (!w.ironman() && (w.ironmanCheckedAt() == null
                || w.ironmanCheckedAt().plus(VerifySettings.IRONMAN_REPROBE).isBefore(now)));
        boolean readIronman = false;
        if (needIronman && (ironmanKnown || probe)) {
            readIronman = true;
            switch (data.hiscores(Board.IRONMAN, w.rsn())) {
                case PublicData.Hiscores.Found f -> {
                    merge(v.hiscores, f.xp());
                    if (!ironmanKnown) store.saveWatch(w.probed(true, now));
                }
                case PublicData.Hiscores.NotListed n -> store.saveWatch(w.probed(false, now));
                // A failed ironman read can't show a skill is unranked there, so nothing read this time is trusted.
                case PublicData.Hiscores.Failed f -> v.hiscoresOk = false;
            }
        }
        if (!v.hiscoresOk) return v;
        switch (data.runeMetrics(w.rsn())) {
            case PublicData.RuneMetrics.Public p -> v.runeMetrics = p.xp10();
            case PublicData.RuneMetrics.Private p -> v.runeMetrics = null;
            case PublicData.RuneMetrics.NotFound n -> v.runeMetrics = null;
            // RuneMetrics is only used to refuse, so not knowing it would let a value it already shows through. Treat as a failed read.
            case PublicData.RuneMetrics.Failed f -> v.hiscoresOk = false;
        }
        v.readAt = clock.instant();
        if (v.hiscoresOk) cache.put(key, new CachedView(v, readIronman));
        return v;
    }

    private static void merge(long[] into, long[] from) {
        for (int i = 0; i < Skills.COUNT && i < from.length; i++) into[i] = Math.max(into[i], from[i]);
    }

    /** At most {@code checksPerMinute} fresh checks (two or three requests each) in any minute, service-wide. */
    private boolean globalAllows(Instant now) {
        long cutoff = now.minus(Duration.ofMinutes(1)).toEpochMilli();
        while (!globalReads.isEmpty() && globalReads.peekFirst() <= cutoff) globalReads.pollFirst();
        return globalReads.size() < settings.checksPerMinute();
    }

    private void noteGlobalCheck(Instant now) {
        globalReads.addLast(now.toEpochMilli());
        if (cache.size() > 2000) cache.clear();
    }

    // =====================================================================================================================
    // Views
    // =====================================================================================================================

    /** The state of one link for the player's page and the owner's view. No XP values, only counts and times. */
    public Map<String, Object> describe(UUID linkId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", settings.enabled());
        Watch w = store.watch(linkId).orElse(null);
        Map<ClaimStatus, Integer> counts = store.claimCounts(linkId);
        int open = counts.getOrDefault(ClaimStatus.PENDING, 0) + counts.getOrDefault(ClaimStatus.UNCHECKED, 0);
        String state;
        if (w != null && w.corroboratedAt() != null) state = "corroborated";
        else if (!settings.enabled()) state = "off";
        else if (w == null) state = "not_started";
        else if (open > 0) state = "waiting";
        else state = "idle";
        out.put("state", state);
        out.put("matches", counts.getOrDefault(ClaimStatus.MATCH, 0));
        out.put("misses", counts.getOrDefault(ClaimStatus.MISS, 0));
        out.put("needed", settings.minMatches());
        out.put("waiting", open);
        out.put("refusedPublic", counts.getOrDefault(ClaimStatus.REFUSED_PUBLIC, 0));
        out.put("nextCheckAt", w == null || w.nextCheckAt() == null ? null : w.nextCheckAt().toString());
        out.put("lastReportAt", w == null ? null : w.lastReportAt().toString());
        out.put("lastPublishedAt", w == null || w.lastPublishedAt() == null ? null : w.lastPublishedAt().toString());
        out.put("corroboratedAt", w == null || w.corroboratedAt() == null ? null : w.corroboratedAt().toString());
        return out;
    }

    /** Service-wide counts for the owner's abuse page. */
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", settings.enabled());
        out.put("minMatches", settings.minMatches());
        out.put("minGainXp", settings.minGainXp());
        out.put("checksPerLinkPerDay", settings.checksPerLinkPerDay());
        out.put("checksPerMinute", settings.checksPerMinute());
        out.put("totals", store.totals(clock.instant()));
        out.put("budgetRefusals", budgetRefusals);
        out.put("failedReads", failedReads);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Watch w : store.recentlyCorroborated(20)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("linkId", w.linkId().toString());
            row.put("matches", w.matches());
            row.put("misses", w.misses());
            row.put("corroboratedAt", w.corroboratedAt().toString());
            recent.add(row);
        }
        out.put("recent", recent);
        return out;
    }

    /** Hourly: deletes old settled claims and idle watches. */
    public int sweep() {
        return store.sweep(clock.instant().minus(VerifySettings.KEPT));
    }

    // =====================================================================================================================

    static String normaliseState(String raw) {
        if (raw == null) return "game";
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "lobby" -> "lobby";
            case "logout", "loginscreen", "login_screen" -> "logout";
            default -> "game";
        };
    }

    private String evidence(Watch w, int matches, int misses) {
        List<String> skills = new ArrayList<>();
        // Names only, no XP: the evidence is shown to the owner and the player.
        for (Claim c : store.matchedClaims(w.linkId())) if (!skills.contains(Skills.name(c.skill()))) skills.add(Skills.name(c.skill()));
        StringBuilder s = new StringBuilder("{\"matches\":").append(matches).append(",\"misses\":").append(misses).append(",\"skills\":[");
        for (int i = 0; i < skills.size(); i++) s.append(i == 0 ? "" : ",").append('"').append(skills.get(i)).append('"');
        s.append("],\"minGainXp\":").append(settings.minGainXp());
        if (w.lastPublishedAt() != null) s.append(",\"publishedAt\":\"").append(w.lastPublishedAt()).append('"');
        return s.append('}').toString();
    }
}
