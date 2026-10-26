package com.younglings.bot.events;

import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRepository.Participant;
import com.younglings.bot.events.EventRules.Source;
import com.younglings.bot.runeclans.RuneClansHubClient;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Feeds running competitions that count XP from RuneSync ({@code xpSource = plugin}, T-537) from the RuneClans hub's XP ledger. Every minute, for
 * each such competition, one {@code POST /v1/guilds/{guildId}/xp/gains} with its players' aliases asks what each gained from when they started
 * counting (the later of the event's start and when they joined) until now, timed XP only: catch-up XP (gained while RuneSync wasn't watching) never
 * scores. What is new since the last pass goes in as {@link Source#IMPORT} progress, exactly as {@link EventTracker} adds RuneMetrics gains, so
 * standings, wrap-ups and prizes need nothing new. The hub's {@code from} is the baseline, so there is no baseline table.
 * <p>
 * Players the hub can't count (no RuneSync, no XP shared with this bot, or no identity grant to match them by) score nothing and are listed by
 * {@link #notCounted} for the event page. With {@code confirmedLinksOnly} (T-552), a player the hub counts but whose RS Link it hasn't confirmed from
 * public data scores nothing either, listed apart as {@code unconfirmed}. Runs only while the bot talks to the hub ({@code RUNECLANS_HUB_ENABLED}); otherwise the option is not
 * even offered.
 * <p>
 * Auto competitions ({@code xpSource = auto}, T-557) are read here too, but only for the players the hub counts and {@link EventTracker} doesn't
 * already own; the others are RuneMetrics' ({@link #runeSyncPlayers} tells it which to leave alone). {@link EventTracker} still reads RuneMetrics
 * for the RuneSync players and reports their gain to {@link #check}: a large disagreement is listed for organisers ({@link #disagreements}),
 * never corrected on its own.
 * <p>
 * Bingo and scavenger XP tiles (T-543, {@code EVENT_XP_TILES_ENABLED}, off by default) are fed by the same pass ({@link #feedTiles}): a tile labelled
 * "Gain 1M Fletching XP" gets the player's timed gain in that skill while the event runs (from the later of its start and when they joined, until
 * now or its planned end), as {@link Source#AUTO} progress, completing when the gain reaches the goal ({@link PluginObjectiveMatcher#xpProgress}).
 * Each entry is keyed by the player, the tile and the progress it builds on, so a re-read (or two passes at once, even from two bot instances)
 * never counts twice: whichever writes first wins and the other is refused. Players without RuneSync (RuneMetrics only) make no progress and are
 * listed by {@link #notCounted} like a competition's.
 */
@BService
public class PluginXpTracker {
    private static final Logger log = LoggerFactory.getLogger(PluginXpTracker.class);
    private static final Duration PERIOD = Duration.ofMinutes(1);
    /** How long a Discord account's alias (or the lack of one) is kept before the hub is asked again. */
    static final Duration ALIAS_KEPT = Duration.ofMinutes(15);
    /** The hub takes at most this many players per gains call. */
    static final int MAX_PLAYERS_PER_CALL = 500;

    /** Who in one competition scored nothing on the last pass, and when that was: {@code unconfirmed} only for "count confirmed links only". */
    public record NotCounted(Set<Long> userIds, Set<Long> unconfirmed, Instant checkedAt) {}

    /** A RuneSync-counted player whose RuneMetrics gain disagrees with RuneSync's ({@link CompetitionTracking#disagrees}). */
    public record Disagreement(long userId, long runeSyncXp, long runeMetricsXp, Instant checkedAt) {}

    private record CachedAlias(Optional<String> alias, Instant until) {}

    private final EventRepository repository;
    private final EventService service;
    private final RuneClansHubClient hub;
    private final ScheduledExecutorService executor = BotScheduler.lane("plugin-xp-tracker");
    private final Map<Long, CachedAlias> aliases = new ConcurrentHashMap<>();
    private final Map<Long, NotCounted> notCounted = new ConcurrentHashMap<>();
    /** Auto competitions: the players this tracker counts (eventId → userIds), so {@link EventTracker} leaves them alone. */
    private final Map<Long, Set<Long>> runeSyncPlayers = new ConcurrentHashMap<>();
    /** Auto competitions: eventId → userId → the latest disagreement. */
    private final Map<Long, Map<Long, Disagreement>> disagreements = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();
    private volatile boolean tiles;

    public PluginXpTracker(EventRepository repository, EventService service, RuneClansHubClient hub) {
        this.repository = repository;
        this.service = service;
        this.hub = hub;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!hub.enabled()) return;
        CompetitionTemplate.offerPluginSource(true);
        tiles = com.younglings.bot.config.BotConfig.getInstance().getEventXpTilesEnabled();
        PluginObjectiveMatcher.offerXpTiles(tiles);
        if (tiles) log.info("Bingo and scavenger XP tiles are counted from the RuneClans hub (EVENT_XP_TILES_ENABLED)");
        executor.scheduleWithFixedDelay(this::sweep, 1, PERIOD.toMinutes(), TimeUnit.MINUTES);
    }

    /** For tests. */
    PluginXpTracker withClock(Clock clock) {
        this.clock = clock;
        return this;
    }

    /** For tests: whether XP tiles are counted. */
    PluginXpTracker withTiles(boolean on) {
        this.tiles = on;
        return this;
    }

    /** The players of a competition counted from RuneSync who scored nothing on the last pass; empty before the first pass or for other events. */
    public Optional<NotCounted> notCounted(long eventId) {
        return Optional.ofNullable(notCounted.get(eventId));
    }

    /** In an auto competition, the players counted from RuneSync on the last pass (and those it already owns); empty for other events. */
    public Set<Long> runeSyncPlayers(long eventId) {
        return runeSyncPlayers.getOrDefault(eventId, Set.of());
    }

    /** In an auto competition, the RuneSync players whose RuneMetrics gain disagrees with RuneSync's, by user id. */
    public List<Disagreement> disagreements(long eventId) {
        return List.copyOf(disagreements.getOrDefault(eventId, Map.of()).values());
    }

    /**
     * From {@link EventTracker}: a RuneSync player's RuneMetrics gain in an auto competition, compared with what RuneSync counted. A disagreement
     * is kept (and logged once) until the numbers agree again.
     */
    public void check(long eventId, long userId, long runeSyncXp, long runeMetricsXp) {
        Map<Long, Disagreement> forEvent = disagreements.computeIfAbsent(eventId, k -> new ConcurrentHashMap<>());
        if (CompetitionTracking.disagrees(runeSyncXp, runeMetricsXp)) {
            Disagreement before = forEvent.put(userId, new Disagreement(userId, runeSyncXp, runeMetricsXp, clock.instant()));
            if (before == null) log.info("Competition {}: RuneSync and RuneMetrics disagree for a player ({} vs {} XP)", eventId, runeSyncXp, runeMetricsXp);
        } else {
            forEvent.remove(userId);
        }
    }

    /** Every running competition counted from RuneSync (or auto), including clan-vs-clan events whose clans play one. */
    private List<Event> competitions() {
        List<Event> all = new ArrayList<>();
        for (Event e : repository.runningEvents(CompetitionTemplate.KEY)) if (fromPlugin(e)) all.add(e);
        for (Event e : repository.runningEvents(ClanVsClan.KEY)) if (ClanVsClan.plays(e, CompetitionTemplate.KEY) && fromPlugin(e)) all.add(e);
        return all;
    }

    /** Every running bingo and scavenger hunt (also as a clan-vs-clan game), for their XP tiles. */
    private List<Event> tileEvents() {
        List<Event> all = new ArrayList<>(repository.runningEvents(BingoTemplate.KEY));
        all.addAll(repository.runningEvents(ScavengerHuntTemplate.KEY));
        for (Event e : repository.runningEvents(ClanVsClan.KEY)) if (ClanVsClan.plays(e, BingoTemplate.KEY) || ClanVsClan.plays(e, ScavengerHuntTemplate.KEY)) all.add(e);
        return all;
    }

    private static boolean fromPlugin(Event e) {
        try {
            return CompetitionTemplate.readsPlugin(DataObject.fromJson(e.config()));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** One pass over every such competition. Never throws: one event's trouble must not stop the next. */
    public void sweep() {
        try {
            List<Event> running = competitions();
            Set<Long> ids = new LinkedHashSet<>();
            for (Event event : running) {
                ids.add(event.id());
                try {
                    feed(event);
                } catch (RuntimeException e) {
                    log.warn("Could not feed competition {} from RuneSync", event.id(), e);
                }
            }
            for (Event event : tiles ? tileEvents() : List.<Event>of()) {
                ids.add(event.id());
                try {
                    feedTiles(event);
                } catch (RuntimeException e) {
                    log.warn("Could not feed the XP tiles of event {} from RuneSync", event.id(), e);
                }
            }
            notCounted.keySet().retainAll(ids);
            runeSyncPlayers.keySet().retainAll(ids);
            disagreements.keySet().retainAll(ids);
        } catch (RuntimeException e) {
            log.warn("The RuneSync competition sweep failed", e);
        }
    }

    /** Brings one competition up to date from the hub. Returns how many players got new progress. */
    public int feed(Event event) {
        DataObject settings = DataObject.fromJson(event.config());
        boolean auto = CompetitionTemplate.countsAuto(settings);
        int skill = CompetitionTemplate.skillOf(settings);
        Integer skillId = skill == CompetitionTemplate.OVERALL ? null : skill;
        Instant now = clock.instant();
        Instant started = repository.startedAt(event.id()).orElse(event.startsAt() == null ? now : event.startsAt());
        List<EventRepository.Progress> progress = repository.progress(event.id());
        Map<Long, CompetitionTracking.Counter> owners = auto ? CompetitionTracking.owners(progress) : Map.of();

        boolean confirmedOnly = CompetitionTemplate.confirmedLinksOnly(settings);
        Set<Long> missing = new LinkedHashSet<>();
        Set<Long> unconfirmed = new LinkedHashSet<>();
        // players by alias, grouped by when they started counting: one hub call per group (nearly always one)
        Map<Instant, Map<String, Participant>> groups = new LinkedHashMap<>();
        for (Participant p : repository.participants(event.id())) {
            if (p.waiting()) continue;
            if (owners.get(p.userId()) == CompetitionTracking.Counter.RUNEMETRICS) continue; // auto: RuneMetrics counted them first
            Optional<String> alias = alias(p.userId(), now);
            if (alias.isEmpty()) {
                missing.add(p.userId());
                continue;
            }
            groups.computeIfAbsent(CompetitionTracking.countsFrom(started, p.joinedAt()), k -> new LinkedHashMap<>()).put(alias.get(), p);
        }

        Map<Long, Long> counted = new HashMap<>();
        for (EventRepository.Progress p : progress) {
            if (p.source() == Source.IMPORT && p.objectiveKey().equals(CompetitionTemplate.OBJECTIVE_KEY)) counted.merge(p.userId(), p.amount(), Long::sum);
        }
        int updated = 0;
        for (Map.Entry<Instant, Map<String, Participant>> group : groups.entrySet()) {
            if (!group.getKey().isBefore(now)) continue; // hasn't started counting yet
            List<String> all = new ArrayList<>(group.getValue().keySet());
            for (int i = 0; i < all.size(); i += MAX_PLAYERS_PER_CALL) {
                List<String> part = all.subList(i, Math.min(all.size(), i + MAX_PLAYERS_PER_CALL));
                RuneClansHubClient.Answer answer = hub.xpGains(event.guildId(), part, skillId, group.getKey(), now);
                if (answer == null || !answer.ok() || answer.json() == null) {
                    // the hub is down or its ledger is off: say nothing about who counts, try again next minute
                    if (answer != null && answer.status() != 404) log.warn("Competition {}: the hub answered {} for XP gains", event.id(), answer.status());
                    return updated;
                }
                Set<String> seen = new LinkedHashSet<>();
                DataArray players = answer.json().optArray("players").orElse(DataArray.empty());
                for (int j = 0; j < players.length(); j++) {
                    DataObject row = players.getObject(j);
                    Participant p = group.getValue().get(row.getString("alias", ""));
                    if (p == null) continue;
                    seen.add(row.getString("alias"));
                    if (confirmedOnly && !confirmed(row)) {
                        unconfirmed.add(p.userId());
                        continue;
                    }
                    if (record(event, p, row.getLong("xp", 0), counted.getOrDefault(p.userId(), 0L), now)) updated++;
                }
                for (String alias : part) if (!seen.contains(alias)) missing.add(group.getValue().get(alias).userId());
            }
        }
        if (auto) {
            // the hub's players are RuneSync's; whoever it can't count is left to RuneMetrics, so nobody is "not counted"
            Set<Long> mine = new LinkedHashSet<>();
            for (Map<String, Participant> group : groups.values()) for (Participant p : group.values()) if (!missing.contains(p.userId())) mine.add(p.userId());
            owners.forEach((user, counter) -> {
                if (counter == CompetitionTracking.Counter.RUNESYNC) mine.add(user);
            });
            runeSyncPlayers.put(event.id(), Set.copyOf(mine));
        } else {
            notCounted.put(event.id(), new NotCounted(Set.copyOf(missing), Set.copyOf(unconfirmed), now));
        }
        return updated;
    }

    /**
     * Brings the XP tiles of one bingo or scavenger hunt up to date from the hub: one gains call (all skills) per group of players who started
     * counting at the same time, for the span the event runs. Returns how many progress entries were added. An event with no XP tiles costs nothing.
     */
    public int feedTiles(Event event) {
        Map<EventRules.ObjectiveSpec, PluginObjectiveMatcher.XpGoal> xpTiles = PluginObjectiveMatcher.xpTiles(ClanVsClan.game(event), repository.objectives(event.id()));
        if (xpTiles.isEmpty()) {
            notCounted.remove(event.id()); // the last XP tile was taken off: nothing to list any more
            return 0;
        }
        Instant now = clock.instant();
        Instant started = repository.startedAt(event.id()).orElse(event.startsAt() == null ? now : event.startsAt());
        // only XP gained during the event counts: not before its advertised start (started early), not after its planned end (finished late)
        if (event.startsAt() != null && event.startsAt().isAfter(started)) started = event.startsAt();
        Instant to = event.endsAt() != null && event.endsAt().isBefore(now) ? event.endsAt() : now;
        Set<Long> missing = new LinkedHashSet<>();
        Map<Instant, Map<String, Participant>> groups = new LinkedHashMap<>();
        for (Participant p : repository.participants(event.id())) {
            if (p.waiting()) continue;
            Optional<String> alias = alias(p.userId(), now);
            if (alias.isEmpty()) {
                missing.add(p.userId());
                continue;
            }
            groups.computeIfAbsent(CompetitionTracking.countsFrom(started, p.joinedAt()), k -> new LinkedHashMap<>()).put(alias.get(), p);
        }
        // what each player already brought each tile: objectiveKey -> userId -> amount
        Map<String, Map<Long, Long>> counted = new HashMap<>();
        for (EventRepository.Progress p : repository.progress(event.id())) {
            if (p.sourceKey() != null && p.sourceKey().startsWith(TILE_KEY_PREFIX)) counted.computeIfAbsent(p.objectiveKey(), k -> new HashMap<>()).merge(p.userId(), p.amount(), Long::sum);
        }
        int updated = 0;
        for (Map.Entry<Instant, Map<String, Participant>> group : groups.entrySet()) {
            if (!group.getKey().isBefore(to)) continue; // hasn't started counting yet, or joined after the event ended
            List<String> all = new ArrayList<>(group.getValue().keySet());
            for (int i = 0; i < all.size(); i += MAX_PLAYERS_PER_CALL) {
                List<String> part = all.subList(i, Math.min(all.size(), i + MAX_PLAYERS_PER_CALL));
                RuneClansHubClient.Answer answer = hub.xpGains(event.guildId(), part, null, group.getKey(), to);
                if (answer == null || !answer.ok() || answer.json() == null) {
                    if (answer != null && answer.status() != 404) log.warn("Event {}: the hub answered {} for XP gains", event.id(), answer.status());
                    return updated;
                }
                Set<String> seen = new LinkedHashSet<>();
                DataArray players = answer.json().optArray("players").orElse(DataArray.empty());
                for (int j = 0; j < players.length(); j++) {
                    DataObject row = players.getObject(j);
                    Participant p = group.getValue().get(row.getString("alias", ""));
                    if (p == null) continue;
                    seen.add(row.getString("alias"));
                    for (var tile : xpTiles.entrySet()) {
                        long already = counted.getOrDefault(tile.getKey().key(), Map.of()).getOrDefault(p.userId(), 0L);
                        if (recordTile(event, p, tile.getKey(), tile.getValue(), gainedIn(row, tile.getValue().skillId()), already)) updated++;
                    }
                }
                for (String alias : part) if (!seen.contains(alias)) missing.add(group.getValue().get(alias).userId());
            }
        }
        notCounted.put(event.id(), new NotCounted(Set.copyOf(missing), Set.of(), now));
        return updated;
    }

    /** A gains row's XP in one skill ({@code bySkill}), or its total for {@link CompetitionTemplate#OVERALL}; 0 when the skill isn't listed. */
    static long gainedIn(DataObject row, int skillId) {
        if (skillId == CompetitionTemplate.OVERALL) return row.getLong("xp", 0);
        DataArray bySkill = row.optArray("bySkill").orElse(DataArray.empty());
        for (int i = 0; i < bySkill.length(); i++) {
            DataObject s = bySkill.getObject(i);
            if (s.getInt("skillId", -1) == skillId) return s.getLong("xp", 0);
        }
        return 0;
    }

    /** The start of an XP tile's progress keys. */
    static final String TILE_KEY_PREFIX = "xpt:";

    /**
     * An XP tile's progress key: the player, the tile and the progress already counted that this entry builds on. Two passes that start from the
     * same counted total (a re-read, or two bot instances at once) get the same key, so only the first is written, whatever each read.
     */
    static String tileSourceKey(long userId, String objectiveKey, long builtOn) {
        return TILE_KEY_PREFIX + userId + ":" + objectiveKey + ":" + builtOn;
    }

    private boolean recordTile(Event event, Participant player, EventRules.ObjectiveSpec tile, PluginObjectiveMatcher.XpGoal goal, long gained, long already) {
        long reaches = PluginObjectiveMatcher.xpProgress(goal, tile, gained);
        if (reaches <= already) return false;
        try {
            service.recordProgress(event.guildId(), event.id(), tile.key(), player.userId(), reaches - already, Source.AUTO, tileSourceKey(player.userId(), tile.key(), already), null,
                    String.format(java.util.Locale.ROOT, "RuneSync: %,d %s gained", gained, goal.what()));
        } catch (EventProblem e) {
            // already complete, locked behind another tile, not in a team yet, or another pass built on this total first: nothing to add right now
            log.debug("Event {}: XP tile {} not counted for {}: {}", event.id(), tile.key(), player.userId(), e.getMessage());
            return false;
        }
        return true;
    }

    /** The hub confirmed the player's RS Link from public data ({@code verification} on the gains row; an older hub without it: not confirmed). */
    static boolean confirmed(DataObject row) {
        String level = row.getString("verification", "implicit");
        return level.equals("public_data_corroborated") || level.equals("jagex_signed");
    }

    private boolean record(Event event, Participant player, long gain, long alreadyCounted, Instant now) {
        long add = CompetitionTracking.toRecord(0, gain, alreadyCounted);
        if (add == 0) return false;
        try {
            service.recordProgress(event.guildId(), event.id(), CompetitionTemplate.OBJECTIVE_KEY, player.userId(), add, Source.IMPORT,
                    CompetitionTracking.pluginSourceKey(player.userId(), now), null, "Tracked from RuneSync");
        } catch (EventProblem e) {
            // already complete, or the player isn't scored yet (no team): nothing to add right now
            if (e.status() != 409) log.warn("Competition {}: could not add RuneSync progress for {}: {}", event.id(), player.userId(), e.getMessage());
            return false;
        }
        return true;
    }

    /** The player's alias for this bot, asked of the hub at most every {@link #ALIAS_KEPT}. */
    private Optional<String> alias(long userId, Instant now) {
        CachedAlias cached = aliases.get(userId);
        if (cached != null && cached.until().isAfter(now)) return cached.alias();
        Optional<String> alias = hub.aliasOf(userId);
        aliases.put(userId, new CachedAlias(alias, now.plus(ALIAS_KEPT)));
        if (aliases.size() > 50_000) aliases.entrySet().removeIf(e -> !e.getValue().until().isAfter(now));
        return alias;
    }
}
