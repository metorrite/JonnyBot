package com.younglings.bot.runescape;

import com.younglings.bot.tracking.ClassifiedEntry;
import com.younglings.bot.tracking.TrackingEventClassifier;
import com.younglings.bot.tracking.TrackingEventRouter;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@BService
public class RuneScapeStatsService {
    private final RuneScapeApiClient apiClient;
    private final PlayerLinkRepository repository;
    private final TrackingEventClassifier classifier;
    private final TrackingEventRouter router;
    private final ClanMemberRepository clanMemberRepository;
    private final SlowPollQueue slowPollQueue;

    // Set once JDA is ready (see #onJdaReady) — same reasoning/pattern as InternalApiServer's own
    // `jda` field. Only needed to resolve a Guild to post tracking announcements to; every other
    // method here works fine without it, so it's fine to be null briefly right after boot.
    private volatile JDA jda;

    public RuneScapeStatsService(RuneScapeApiClient apiClient, PlayerLinkRepository repository,
                                  TrackingEventClassifier classifier, TrackingEventRouter router,
                                  ClanMemberRepository clanMemberRepository, SlowPollQueue slowPollQueue) {
        this.apiClient = apiClient;
        this.repository = repository;
        this.classifier = classifier;
        this.router = router;
        this.clanMemberRepository = clanMemberRepository;
        this.slowPollQueue = slowPollQueue;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
    }

    /**
     * A player's data belongs to the player, not to a server: one poll, one stored snapshot, whichever server asked, and
     * every server reads the same history.
     * <p>
     * Fetches the player's current profile and saves a snapshot of it — the per-skill breakdown
     * and any new activities go into their own tables (see {@link PlayerLinkRepository}), not just
     * the summary row. Empty if the profile couldn't be fetched (private, doesn't exist, or the
     * request failed) — nothing is saved in that case. See {@link #pollAndSnapshotResult} to tell
     * those failure reasons apart.
     */
    public Optional<RuneScapeProfile> pollAndSnapshot(String rsn) {
        return pollAndSnapshotResult(rsn) instanceof ProfileResult.Found(var profile)
                ? Optional.of(profile) : Optional.empty();
    }

    /**
     * Same fetch-and-save as {@link #pollAndSnapshot}, but keeps the reason a failure happened
     * instead of collapsing it to empty. Always writes a fresh snapshot on a successful fetch, even
     * if nothing about the player changed since last time — a database write here is cheap, and
     * keeping "polled X ago" accurate and the code simple is worth more than skipping an unchanged
     * write. (An earlier version of this method kept an in-memory copy of each player's last-known
     * stats specifically to skip that write; that traded a negligible amount of database traffic for
     * held-in-memory state, the wrong side of that tradeoff for this app — see the memory/cost report
     * from 2026-09-26.)
     * <p>
     * A rate-limited ({@code HTTP 429}) fetch is queued in {@link SlowPollQueue} for a slower retry
     * rather than saved or dispatched — {@link SlowPollScheduler} calls right back into this same
     * method later, so a repeat rate-limit backs off further automatically instead of needing its own
     * handling here.
     */
    public ProfileResult pollAndSnapshotResult(String rsn) {
        ProfileResult result = apiClient.fetchProfileResult(rsn);
        if (result instanceof ProfileResult.RateLimited(var retryAfter)) {
            slowPollQueue.enqueue(rsn, retryAfter);
        } else if (result instanceof ProfileResult.Found(var profile)) {
            long snapshotId = repository.saveSnapshot(rsn, profile, serializeSkills(profile.skills()));
            repository.saveSkillSnapshot(snapshotId, profile.skills());
            List<PlayerActivity> newActivities = repository.saveActivities(rsn, profile.activities());
            dispatchNewActivities(rsn, newActivities);
        }
        return result;
    }

    /**
     * Announces what a poll found to every server whose clan this player is currently in: the same player in two servers'
     * clans (or the same clan tracked by two servers) is announced in each, from the one poll. No-ops if JDA isn't ready
     * yet or nothing new classified, because the tracking feed is a bonus on top of polling, never a reason to fail it.
     * Servers the player isn't a clan member of hear nothing: polling and the personal {@code /rs} profile work for
     * anyone linked, but the tracking feed is a clan-wide announcement channel, not an "every RSN anyone has linked" one.
     */
    private void dispatchNewActivities(String rsn, List<PlayerActivity> newActivities) {
        if (newActivities.isEmpty()) return;

        JDA currentJda = jda;
        if (currentJda == null) return;
        List<Guild> guilds = new ArrayList<>();
        for (long guildId : clanMemberRepository.activeGuildIds(rsn)) {
            Guild guild = currentJda.getGuildById(guildId);
            if (guild != null) guilds.add(guild);
        }
        if (guilds.isEmpty()) return;

        List<ClassifiedEntry> entries = new ArrayList<>();
        for (ActivityRun run : collapseConsecutive(newActivities)) {
            classifier.classify(rsn, run.activity(), run.count()).ifPresent(entries::add);
        }
        for (Guild guild : guilds) router.dispatchAll(guild, entries);
    }

    private record ActivityRun(PlayerActivity activity, int count) {}

    private record TimedActivity(PlayerActivity activity, java.time.LocalDateTime time) {}

    // A farming session's kills (or a grab of the same drop landing more than once) don't actually
    // need to be back-to-back in RuneMetrics' own feed to belong together — a level-up, a different
    // drop, or any other activity logged mid-session shouldn't split "defeated Arch-Glacor x30" into
    // several shorter runs just because it happened to interrupt the raw list. A gap this size or
    // bigger between two same-activity entries is treated as two separate sessions instead (so an
    // actual return trip hours later still reports as its own line, not folded into a stale run).
    private static final Duration SAME_RUN_GAP = Duration.ofHours(1);

    /**
     * RuneMetrics reports every individual repeat of the same activity (a grind session killing the
     * same boss, or landing the same drop twice at once) as its own separate line rather than
     * aggregating them itself — left as-is, this would flood the tracking feed with one identical line
     * per repeat. Groups every activity in the batch by identical ({@link PlayerActivity#text()},
     * {@link PlayerActivity#details()}) — not just adjacent occurrences, so something else logged
     * mid-session doesn't fracture the run — then splits each group back into separate runs wherever
     * two consecutive repeats (by RuneMetrics' own relative timestamp) are more than {@link #SAME_RUN_GAP}
     * apart. {@link TrackingEventClassifier} sees each run's whole count and renders it as one line
     * ("defeated X 10 times") instead of ten; the final list is re-sorted chronologically so dispatch
     * order still reads top-to-bottom like the raw feed did.
     */
    private static List<ActivityRun> collapseConsecutive(List<PlayerActivity> activities) {
        Map<String, List<TimedActivity>> byActivity = new LinkedHashMap<>();
        for (PlayerActivity activity : activities) {
            byActivity.computeIfAbsent(activityKey(activity), k -> new ArrayList<>())
                    .add(new TimedActivity(activity, RuneMetricsDates.parse(activity.date())));
        }

        List<ActivityRun> runs = new ArrayList<>();
        for (List<TimedActivity> group : byActivity.values()) {
            group.sort(Comparator.comparing(TimedActivity::time));

            TimedActivity latestInRun = null;
            int count = 0;
            for (TimedActivity timed : group) {
                if (latestInRun != null && Duration.between(latestInRun.time(), timed.time()).compareTo(SAME_RUN_GAP) <= 0) {
                    count++;
                    latestInRun = timed; // group is sorted ascending, so this is always the latest so far
                } else {
                    if (latestInRun != null) runs.add(new ActivityRun(latestInRun.activity(), count));
                    latestInRun = timed;
                    count = 1;
                }
            }
            if (latestInRun != null) runs.add(new ActivityRun(latestInRun.activity(), count));
        }

        runs.sort(Comparator.comparing(run -> RuneMetricsDates.parse(run.activity().date())));
        return runs;
    }

    private static String activityKey(PlayerActivity activity) {
        return activity.text() + "\u0000" + activity.details();
    }

    /** The RuneMetrics request counters so far, so a polling pass can report what it cost (see {@link RequestPacer.Stats#minus}). */
    public RequestPacer.Stats requestStats() {
        return apiClient.paceStats();
    }

    public PlayerLinkRepository.StatsSnapshotRow getLatestSnapshot(String rsn) {
        return repository.getLatestSnapshot(rsn);
    }

    /** Snapshot history, most recent first — the data source for a "gains over time" view. */
    public List<PlayerLinkRepository.StatsSnapshotRow> getSnapshotHistory(String rsn, int limit) {
        return repository.getSnapshotHistory(rsn, limit);
    }

    public List<SkillValue> getSkillsForSnapshot(long snapshotId) {
        return repository.getSkillsForSnapshot(snapshotId);
    }

    public List<PlayerActivity> getRecentActivities(String rsn, int limit) {
        return repository.getRecentActivities(rsn, limit);
    }

    /** One skill's XP at each poll over the last {@code days} days, oldest first — the XP chart's data source. */
    public List<SkillXpPoint> getSkillXpHistory(String rsn, int skillId, int days) {
        return repository.getSkillXpHistory(rsn, skillId, java.time.OffsetDateTime.now().minusDays(days));
    }

    /** Every snapshot since {@code since}, oldest first. */
    public List<PlayerLinkRepository.StatsSnapshotRow> getSnapshotsSince(String rsn, java.time.OffsetDateTime since) {
        return repository.getSnapshotsSince(rsn, since);
    }

    /** Activities recorded since {@code since}, oldest first. */
    public List<PlayerActivity> getActivitiesSince(String rsn, java.time.OffsetDateTime since) {
        return repository.getActivitiesSince(rsn, since);
    }

    /** Every skill's XP at every poll since {@code since} in one query — the stacked-bar chart's data source. */
    public List<PlayerLinkRepository.SkillHistoryPoint> getAllSkillsXpHistorySince(String rsn, java.time.OffsetDateTime since) {
        return repository.getAllSkillsXpHistorySince(rsn, since);
    }

    static String serializeSkills(List<SkillValue> skills) {
        DataArray array = DataArray.empty();
        for (SkillValue skill : skills) {
            array.add(DataObject.empty()
                    .put("id", skill.skillId())
                    .put("level", skill.level())
                    .put("xp", skill.xp())
                    .put("rank", skill.rank()));
        }
        return array.toString();
    }
}
