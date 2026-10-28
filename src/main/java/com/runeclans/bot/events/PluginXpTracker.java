package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRules.Source;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.util.BotScheduler;
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
import java.util.HashSet;
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
 * {@link #notCounted} for the event page. With {@code confirmedLinksOnly} (T-552), a player the hub counts but whose RuneLink it hasn't confirmed from
 * public data scores nothing either, listed apart as {@code unconfirmed}. Runs only while the bot talks to the hub ({@code RUNECLANS_HUB_ENABLED}); otherwise the option is not
 * even offered.
 * <p>
 * Auto competitions ({@code xpSource = auto}, T-557) are read here too, but only for the players the hub counts and {@link EventTracker} doesn't
 * already own; the others are RuneMetrics' ({@link #runeSyncPlayers} tells it which to leave alone). {@link EventTracker} still reads RuneMetrics
 * for the RuneSync players and reports their gain to {@link #check}: a large disagreement is listed for organisers ({@link #disagreements}),
 * never corrected on its own.
 * <p>
 * Bingo and scavenger XP tiles (T-543, {@code EVENT_XP_TILES_ENABLED}, off by default) are fed by the same pass ({@link #feedTiles}): a tile labelled
 * "Gain 1M Fletching XP" gets the player's (or their team's, added up) timed gain in that skill while the event runs (from the later of its start,
 * when they joined and when the step unlocked, until now or its planned end), as {@link Source#AUTO} progress, completing when the gain reaches the
 * goal ({@link PluginObjectiveMatcher#xpProgress}). Each entry is keyed by the player or team, the tile and the progress it builds on, so a re-read
 * (or two passes at once, even from two bot instances) never counts twice: whichever writes first wins and the other is refused. Players without
 * RuneSync (RuneMetrics only) make no progress and are listed by {@link #notCounted} like a competition's.
 * <p>
 * The hub calls an XP tile pass makes stay bounded (T-706): a start of counting after the event's start is rounded up to the next
 * {@link #COALESCE} step, so players whose steps unlocked minutes apart share one read, and a pass makes at most {@link #MAX_TILE_CALLS_PER_PASS}
 * calls, asking the spans asked longest ago first (one the hub failed on behind the rest) and using each other span's last answer meanwhile (a gain only grows, so an older answer can only
 * count less, never more). The first call that fails ends the pass. A player moved to another team counts for the new team from the move. Once an
 * organiser moves the event to scoring, one final pass (within {@link #FINAL_WINDOW}) reads every span, exactly, up to the planned end or the move
 * to scoring, whichever is sooner, so the last minutes before the finish aren't lost ({@link EventService#finalXpTile}).
 */
@BService
public class PluginXpTracker {
    private static final Logger log = LoggerFactory.getLogger(PluginXpTracker.class);
    private static final Duration PERIOD = Duration.ofMinutes(1);
    /** How long a Discord account's alias (or the lack of one) is kept before the hub is asked again. */
    static final Duration ALIAS_KEPT = Duration.ofMinutes(15);
    /** After the hub couldn't be asked for an alias, how long no alias lookup is tried at all (one try per pass while it is down). */
    static final Duration ALIAS_RETRY = Duration.ofMinutes(1);
    /** The hub takes at most this many players per gains call. */
    static final int MAX_PLAYERS_PER_CALL = 500;
    /** XP tiles: a start of counting after the event's start is rounded up to a step of this, so nearby unlocks share one hub read. */
    static final Duration COALESCE = Duration.ofMinutes(5);
    /** XP tiles: at most this many hub calls per event per pass while it runs (the final pass reads everything). */
    static final int MAX_TILE_CALLS_PER_PASS = 6;
    /** How long after an event moves to scoring its final XP tile pass is still tried. */
    static final Duration FINAL_WINDOW = Duration.ofMinutes(10);

    /** Who in one competition scored nothing on the last pass, and when that was: {@code unconfirmed} only for "count confirmed links only". */
    public record NotCounted(Set<Long> userIds, Set<Long> unconfirmed, Instant checkedAt) {}

    /** A RuneSync-counted player whose RuneMetrics gain disagrees with RuneSync's ({@link CompetitionTracking#disagrees}). */
    public record Disagreement(long userId, long runeSyncXp, long runeMetricsXp, Instant checkedAt) {}

    private record CachedAlias(Optional<String> alias, Instant until) {}

    /** One XP tile span's last gains answer (rows by alias), and when it was read. */
    private record TileRead(Map<String, DataObject> rows, Instant at) {}

    private final EventRepository repository;
    private final EventService service;
    private final RuneClansHubClient hub;
    private final ScheduledExecutorService executor = BotScheduler.lane("plugin-xp-tracker");
    private final Map<Long, CachedAlias> aliases = new ConcurrentHashMap<>();
    private volatile Instant aliasLookupsPausedUntil = Instant.MIN;
    private final Map<Long, NotCounted> notCounted = new ConcurrentHashMap<>();
    /** Auto competitions: the players this tracker counts (eventId → userIds), so {@link EventTracker} leaves them alone. */
    private final Map<Long, Set<Long>> runeSyncPlayers = new ConcurrentHashMap<>();
    /** Auto competitions: eventId → userId → the latest disagreement. */
    private final Map<Long, Map<Long, Disagreement>> disagreements = new ConcurrentHashMap<>();
    /** One lock per competition, so {@link #pass} never runs twice at once for it (kept for the bot's lifetime: one small object per competition). */
    private final Map<Long, Object> passing = new ConcurrentHashMap<>();
    /** XP tiles: eventId → each span's last answer, so a pass that reads only some spans still adds up whole teams. */
    private final Map<Long, Map<Span, TileRead>> tileReads = new ConcurrentHashMap<>();
    /**
     * XP tiles: eventId → when each span was last asked of the hub and whether that failed, so one that keeps failing goes to the back (behind
     * those asked in the same pass that answered), not the front.
     */
    private final Map<Long, Map<Span, Tried>> tileTried = new ConcurrentHashMap<>();

    /** When an XP tile span was last asked of the hub, and whether the hub failed to answer. */
    private record Tried(Instant at, boolean failed) {
        static final Tried NEVER = new Tried(Instant.MIN, false);
        static final java.util.Comparator<Tried> ORDER = java.util.Comparator.comparing(Tried::at).thenComparing(Tried::failed);
    }
    /** Events being scored whose final XP tile pass is done, with the move to scoring it was for (a reopened and refinished event gets another). */
    private final Map<Long, Instant> finalised = new ConcurrentHashMap<>();
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
        tiles = com.runeclans.bot.config.BotConfig.getInstance().getEventXpTilesEnabled();
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

    /**
     * Every running bingo and scavenger hunt (also as a clan-vs-clan game), for their XP tiles, and those moved to scoring within the last
     * {@link #FINAL_WINDOW}, for their final pass.
     */
    private List<Event> tileEvents() {
        Instant since = clock.instant().minus(FINAL_WINDOW);
        List<Event> all = new ArrayList<>();
        for (String template : List.of(BingoTemplate.KEY, ScavengerHuntTemplate.KEY)) {
            all.addAll(repository.runningEvents(template));
            all.addAll(repository.scoringSince(template, since));
        }
        List<Event> clanVsClan = new ArrayList<>(repository.runningEvents(ClanVsClan.KEY));
        clanVsClan.addAll(repository.scoringSince(ClanVsClan.KEY, since));
        for (Event e : clanVsClan) if (ClanVsClan.plays(e, BingoTemplate.KEY) || ClanVsClan.plays(e, ScavengerHuntTemplate.KEY)) all.add(e);
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
                if (event.status() == EventRules.Status.SCORING && finalised.containsKey(event.id())
                        && repository.scoringAt(event.id()).map(at -> at.equals(finalised.get(event.id()))).orElse(true)) continue; // its final pass is done
                try {
                    feedTiles(event);
                } catch (RuntimeException e) {
                    log.warn("Could not feed the XP tiles of event {} from RuneSync", event.id(), e);
                }
            }
            notCounted.keySet().retainAll(ids);
            runeSyncPlayers.keySet().retainAll(ids);
            disagreements.keySet().retainAll(ids);
            tileReads.keySet().retainAll(ids);
            tileTried.keySet().retainAll(ids);
            finalised.keySet().retainAll(ids);
        } catch (RuntimeException e) {
            log.warn("The RuneSync competition sweep failed", e);
        }
    }

    /** One {@link #pass}: how many players got new progress, and whether every player was asked of the hub (false when it couldn't be). */
    public record Pass(int updated, boolean complete) {}

    /** Brings one competition up to date from the hub. Returns how many players got new progress. */
    public int feed(Event event) {
        return pass(event).updated();
    }

    /**
     * {@link #feed}, saying too whether the hub answered for everyone, so a closing pass can tell "nothing new" from "couldn't ask" (T-541).
     * One pass at a time per competition: each reads what is already counted and adds the rest under a per-minute key, so two passes at once
     * (this sweep and skill of the week's closing pass) would both add the same gain.
     */
    public Pass pass(Event event) {
        Object lock = passing.computeIfAbsent(event.id(), k -> new Object());
        synchronized (lock) {
            return passLocked(event);
        }
    }

    private Pass passLocked(Event event) {
        DataObject settings = DataObject.fromJson(event.config());
        boolean auto = CompetitionTemplate.countsAuto(settings);
        int skill = CompetitionTemplate.skillOf(settings);
        Integer skillId = skill == CompetitionTemplate.OVERALL ? null : skill;
        Instant now = clock.instant();
        // skill of the week (T-541): from the planned start to the planned end for everyone, so a late pass never counts past the end
        // the hub's span is [from, to) and endsAt is the span's last second, so the end it is asked up to is the second after it
        boolean wholeSpan = CompetitionTemplate.countsWholeSpan(settings) && event.startsAt() != null;
        Instant end = wholeSpan && event.endsAt() != null ? event.endsAt().plusSeconds(1) : null;
        Instant until = end != null && end.isBefore(now) ? end : now;
        Instant started = wholeSpan ? event.startsAt() : repository.startedAt(event.id()).orElse(event.startsAt() == null ? now : event.startsAt());
        List<EventRepository.Progress> progress = repository.progress(event.id());
        Map<Long, CompetitionTracking.Counter> owners = auto ? CompetitionTracking.owners(progress) : Map.of();

        boolean confirmedOnly = CompetitionTemplate.confirmedLinksOnly(settings);
        Set<Long> missing = new LinkedHashSet<>();
        Set<Long> unconfirmed = new LinkedHashSet<>();
        // players by alias, grouped by their server (a clan-vs-clan guest is read under their clan's) and when they started counting: one hub call
        // per group (nearly always one)
        Map<Span, Map<String, Participant>> groups = new LinkedHashMap<>();
        // players whose alias the hub couldn't be asked for and none was ever kept: what the last pass said about them stands
        Set<Long> unknown = new LinkedHashSet<>();
        boolean complete = true;
        for (Participant p : repository.participants(event.id())) {
            if (p.waiting()) continue;
            if (owners.get(p.userId()) == CompetitionTracking.Counter.RUNEMETRICS) continue; // auto: RuneMetrics counted them first
            if (end != null && p.joinedAt() != null && !p.joinedAt().isBefore(end)) continue; // whole span: joined after it ended, so not in it
            Optional<String> alias;
            try {
                alias = alias(p.userId(), now);
            } catch (RuneClansHubClient.Unreachable e) {
                // the hub is down: an expired alias still beats none, and the pass isn't complete either way
                complete = false;
                CachedAlias kept = aliases.get(p.userId());
                if (kept == null) {
                    unknown.add(p.userId());
                    continue;
                }
                alias = kept.alias();
            }
            if (alias.isEmpty()) {
                missing.add(p.userId());
                continue;
            }
            Span span = new Span(p.guildId(), wholeSpan ? started : CompetitionTracking.countsFrom(started, p.joinedAt()));
            groups.computeIfAbsent(span, k -> new LinkedHashMap<>()).put(alias.get(), p);
        }

        Map<Long, Long> counted = new HashMap<>();
        Set<String> keys = new HashSet<>();
        for (EventRepository.Progress p : progress) {
            if (p.source() == Source.IMPORT && p.objectiveKey().equals(CompetitionTemplate.OBJECTIVE_KEY)) counted.merge(p.userId(), p.amount(), Long::sum);
            if (p.sourceKey() != null) keys.add(p.sourceKey());
        }
        int updated = 0;
        for (Map.Entry<Span, Map<String, Participant>> group : groups.entrySet()) {
            if (!group.getKey().from().isBefore(until)) continue; // hasn't started counting yet
            List<String> all = new ArrayList<>(group.getValue().keySet());
            for (int i = 0; i < all.size(); i += MAX_PLAYERS_PER_CALL) {
                List<String> part = all.subList(i, Math.min(all.size(), i + MAX_PLAYERS_PER_CALL));
                RuneClansHubClient.Answer answer = hub.xpGains(group.getKey().guildId(), part, skillId, group.getKey().from(), until);
                if (answer == null || !answer.ok() || answer.json() == null) {
                    // the hub is down or its ledger is off: say nothing about who counts, try again next minute
                    if (answer != null && answer.status() != 404) log.warn("Competition {}: the hub answered {} for XP gains", event.id(), answer.status());
                    return new Pass(updated, false);
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
                    long add = CompetitionTracking.toRecord(0, row.getLong("xp", 0), counted.getOrDefault(p.userId(), 0L));
                    if (add > 0 && keys.contains(CompetitionTracking.pluginSourceKey(p.userId(), now))) {
                        // another pass already counted this player this minute: the rest is added next minute, and this pass isn't complete
                        complete = false;
                        continue;
                    }
                    if (record(event, p, add, now)) updated++;
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
            for (long user : unknown) if (runeSyncPlayers(event.id()).contains(user)) mine.add(user);
            runeSyncPlayers.put(event.id(), Set.copyOf(mine));
        } else {
            NotCounted before = notCounted.get(event.id());
            if (before != null) {
                for (long user : unknown) {
                    if (before.userIds().contains(user)) missing.add(user);
                    if (before.unconfirmed().contains(user)) unconfirmed.add(user);
                }
            }
            notCounted.put(event.id(), new NotCounted(Set.copyOf(missing), Set.copyOf(unconfirmed), now));
        }
        return new Pass(updated, complete);
    }

    /** One XP gains read: the players' server (the hub counts a player only where their grant covers that server) and where counting starts. */
    private record Span(long guildId, Instant from) {}

    /** A player whose gain counts towards an open XP tile of their subject, read over {@code span}. */
    private record Reader(Participant player, String alias, Span span) {}

    /**
     * Brings the XP tiles of one bingo or scavenger hunt up to date from the hub: one gains call (all skills) per server and start of counting
     * (rounded to {@link #COALESCE}, at most {@link #MAX_TILE_CALLS_PER_PASS} a pass), for the span the event runs. Returns how many progress entries were added. An event with no XP tiles costs nothing, and
     * a tile already complete (however it got there) or still locked is not read.
     * <ul>
     *   <li>A tile's subject is the player, or their team when the event scores by team (clan-vs-clan always does): the team's members' gains add
     *       up, and what the team reaches is recorded once, under the member who gained most, so "Gain 1M Fletching XP" completes from four members
     *       gaining 300k each.</li>
     *   <li>A step that unlocks after another (an ordered scavenger hunt) counts XP from when it unlocked for the subject, as a drop before then
     *       wouldn't count either.</li>
     *   <li>Each player is read under their own server (a clan-vs-clan guest under their clan's), so a grant that covers only that server still counts.</li>
     * </ul>
     */
    public int feedTiles(Event event) {
        List<EventRules.ObjectiveSpec> objectives = repository.objectives(event.id());
        Map<EventRules.ObjectiveSpec, PluginObjectiveMatcher.XpGoal> xpTiles = PluginObjectiveMatcher.xpTiles(ClanVsClan.game(event), objectives);
        if (xpTiles.isEmpty()) {
            notCounted.remove(event.id()); // the last XP tile was taken off: nothing to list any more
            tileReads.remove(event.id());
            tileTried.remove(event.id());
            return 0;
        }
        Instant now = clock.instant();
        // the final pass of an event being scored reads up to when it moved to scoring, every span, exactly
        boolean last = event.status() == EventRules.Status.SCORING;
        Instant stop = now;
        Instant scoringAt = null;
        if (last) {
            Optional<Instant> scoring = repository.scoringAt(event.id());
            if (scoring.isEmpty()) return 0;
            scoringAt = scoring.get();
            if (scoringAt.isBefore(stop)) stop = scoringAt;
        }
        Instant started = repository.startedAt(event.id()).orElse(event.startsAt() == null ? now : event.startsAt());
        // only XP gained during the event counts: not before its advertised start (started early), not after its planned end (finished late)
        if (event.startsAt() != null && event.startsAt().isAfter(started)) started = event.startsAt();
        Instant to = event.endsAt() != null && event.endsAt().isBefore(stop) ? event.endsAt() : stop;
        boolean byTeam = event.scoringMode() == EventRules.ScoringMode.TEAM;
        // a player moved to another team counts for it from the move: what they gained before counted for their old team
        Map<Long, Instant> movedAt = byTeam ? repository.teamJoinedAt(event.id()) : Map.of();
        Map<String, EventRules.ObjectiveSpec> byKey = new HashMap<>();
        for (EventRules.ObjectiveSpec o : objectives) byKey.put(o.key(), o);
        // each subject's progress (a player's, or their team's), in the order it was written
        Map<Long, List<EventRepository.Progress>> logs = new HashMap<>();
        for (EventRepository.Progress p : repository.progress(event.id())) {
            Long subject = byTeam ? p.teamId() : Long.valueOf(p.userId());
            if (subject != null) logs.computeIfAbsent(subject, k -> new ArrayList<>()).add(p);
        }

        Set<Long> missing = new LinkedHashSet<>();
        Map<Span, Map<String, Participant>> asks = new LinkedHashMap<>();
        // subject -> open XP tile -> the players whose gain counts towards it
        Map<Long, Map<EventRules.ObjectiveSpec, List<Reader>>> open = new LinkedHashMap<>();
        for (Participant p : repository.participants(event.id())) {
            if (p.waiting() || !service.mayPlay(event, p)) continue;
            Long subject = byTeam ? p.teamId() : Long.valueOf(p.userId());
            if (subject == null) continue; // not in a team yet: nothing of theirs counts
            Optional<String> alias = alias(p.userId(), now);
            if (alias.isEmpty()) {
                missing.add(p.userId());
                continue;
            }
            List<EventRepository.Progress> history = logs.getOrDefault(subject, List.of());
            for (EventRules.ObjectiveSpec tile : xpTiles.keySet()) {
                if (sum(history, tile.key(), false) >= tile.target() * tile.repeatLimit()) continue; // complete: by RuneSync, proof, an organiser or a teammate
                Instant unlocked = unlockedAt(tile, history, byKey);
                if (unlocked == null) continue; // a step still locked behind another
                Instant from = CompetitionTracking.countsFrom(started, p.joinedAt());
                Instant moved = movedAt.get(p.userId());
                if (moved != null && moved.isAfter(from)) from = moved;
                if (unlocked.isAfter(from)) from = unlocked;
                if (!last) from = coalesced(started, from);
                if (!from.isBefore(to)) continue; // hasn't started counting yet, or joined after the event ended
                Span span = new Span(p.guildId(), from);
                asks.computeIfAbsent(span, k -> new LinkedHashMap<>()).put(alias.get(), p);
                open.computeIfAbsent(subject, k -> new LinkedHashMap<>()).computeIfAbsent(tile, k -> new ArrayList<>()).add(new Reader(p, alias.get(), span));
            }
        }

        // back to running (an organiser reopened it): a later move to scoring gets its own final pass
        if (!last) finalised.remove(event.id());
        // the spans asked longest ago (or never) first, up to the pass's budget; the final pass reads them all
        Map<Span, TileRead> reads = tileReads.computeIfAbsent(event.id(), k -> new ConcurrentHashMap<>());
        reads.keySet().retainAll(asks.keySet());
        Map<Span, Tried> tried = tileTried.computeIfAbsent(event.id(), k -> new ConcurrentHashMap<>());
        tried.keySet().retainAll(asks.keySet());
        List<Span> order = new ArrayList<>(asks.keySet());
        order.sort(java.util.Comparator.comparing(span -> tried.getOrDefault(span, Tried.NEVER), Tried.ORDER));
        int calls = 0;
        int fresh = 0;
        boolean failed = false;
        for (Span span : order) {
            Map<String, Participant> who = asks.get(span);
            int need = (who.size() + MAX_PLAYERS_PER_CALL - 1) / MAX_PLAYERS_PER_CALL;
            if (!last && calls > 0 && calls + need > MAX_TILE_CALLS_PER_PASS) break; // the rest is read on the next passes
            calls += need;
            Map<String, DataObject> rows = gains(event, span, who.keySet(), to);
            tried.put(span, new Tried(now, rows == null));
            if (rows == null) {
                // the hub couldn't answer: stop here, the other spans wait for the next pass (this one behind them)
                failed = true;
                break;
            }
            reads.put(span, new TileRead(rows, now));
            fresh++;
        }
        if (last && failed && !now.isBefore(stop.plus(FINAL_WINDOW).minus(PERIOD))) {
            log.warn("Event {}: the final XP tile pass couldn't read everything before its {} minutes ran out; the last minutes may be missing", event.id(),
                    FINAL_WINDOW.toMinutes());
        }
        // the hub is down or its ledger is off: say nothing about who counts, try again next minute
        if (failed && fresh == 0) return 0;

        // who the hub counts, from each span's latest answer; a player in no span answered yet keeps what the last pass said
        Set<Long> counted = new HashSet<>();
        Set<Long> unread = new HashSet<>();
        for (Map.Entry<Span, Map<String, Participant>> ask : asks.entrySet()) {
            TileRead read = reads.get(ask.getKey());
            ask.getValue().forEach((alias, p) -> {
                if (read == null) unread.add(p.userId());
                else if (read.rows().containsKey(alias)) counted.add(p.userId());
                else missing.add(p.userId());
            });
        }
        NotCounted before = notCounted.get(event.id());
        for (long user : unread) if (!counted.contains(user) && before != null && before.userIds().contains(user)) missing.add(user);

        int updated = 0;
        for (Map.Entry<Long, Map<EventRules.ObjectiveSpec, List<Reader>>> subject : open.entrySet()) {
            for (Map.Entry<EventRules.ObjectiveSpec, List<Reader>> tile : subject.getValue().entrySet()) {
                PluginObjectiveMatcher.XpGoal goal = xpTiles.get(tile.getKey());
                long gained = 0;
                long most = -1;
                Participant top = null;
                for (Reader r : tile.getValue()) {
                    TileRead read = reads.get(r.span());
                    DataObject row = read == null ? null : read.rows().get(r.alias());
                    if (row == null) continue;
                    long gain = gainedIn(row, goal.skillId());
                    gained += gain;
                    if (gain > most) {
                        most = gain;
                        top = r.player();
                    }
                }
                if (top == null) continue;
                long already = sum(logs.getOrDefault(subject.getKey(), List.of()), tile.getKey().key(), true);
                String who = byTeam ? "t" + subject.getKey() : Long.toString(subject.getKey());
                if (recordTile(event, top, tile.getKey(), goal, gained, already, who, byTeam)) updated++;
            }
        }
        notCounted.put(event.id(), new NotCounted(Set.copyOf(missing), Set.of(), now));
        if (last && !failed) finalised.put(event.id(), scoringAt);
        return updated;
    }

    /**
     * Where an XP tile span starts reading: a start after the event's start is rounded up to the next {@link #COALESCE} step from it, so players
     * whose steps unlocked (or who joined or moved) minutes apart share one read. Rounding up never counts XP from before the start it rounds.
     */
    static Instant coalesced(Instant started, Instant from) {
        if (!from.isAfter(started)) return from;
        long step = COALESCE.toNanos();
        long steps = (Duration.between(started, from).toNanos() + step - 1) / step;
        return started.plus(COALESCE.multipliedBy(steps));
    }

    /** One span's gains rows by alias (asked in parts of {@link #MAX_PLAYERS_PER_CALL}); null when the hub couldn't answer. */
    private Map<String, DataObject> gains(Event event, Span span, Set<String> aliases, Instant to) {
        Map<String, DataObject> rows = new HashMap<>();
        List<String> all = new ArrayList<>(aliases);
        for (int i = 0; i < all.size(); i += MAX_PLAYERS_PER_CALL) {
            RuneClansHubClient.Answer answer = hub.xpGains(span.guildId(), all.subList(i, Math.min(all.size(), i + MAX_PLAYERS_PER_CALL)), null, span.from(), to);
            if (answer == null || !answer.ok() || answer.json() == null) {
                if (answer != null && answer.status() != 404) log.warn("Event {}: the hub answered {} for XP gains", event.id(), answer.status());
                return null;
            }
            DataArray players = answer.json().optArray("players").orElse(DataArray.empty());
            for (int j = 0; j < players.length(); j++) rows.put(players.getObject(j).getString("alias", ""), players.getObject(j));
        }
        return rows;
    }

    /** The sum of a subject's progress on one objective, or of only its XP tile entries. */
    private static long sum(List<EventRepository.Progress> history, String objectiveKey, boolean xpTileOnly) {
        long total = 0;
        for (EventRepository.Progress p : history) {
            if (p.objectiveKey().equals(objectiveKey) && (!xpTileOnly || p.sourceKey() != null && p.sourceKey().startsWith(TILE_KEY_PREFIX))) total += p.amount();
        }
        return total;
    }

    /**
     * When a tile opened for a subject, from its progress in the order written ({@link EventScoring#available}): {@link Instant#MIN} for a tile with
     * no prerequisites, null while it is locked. A step reopened after an organiser took a prerequisite back counts from the reopening.
     */
    static Instant unlockedAt(EventRules.ObjectiveSpec tile, List<EventRepository.Progress> history, Map<String, EventRules.ObjectiveSpec> byKey) {
        if (tile.prerequisites().isEmpty()) return Instant.MIN;
        Map<String, Long> totals = new HashMap<>();
        Instant since = null;
        for (EventRepository.Progress p : history) {
            if (!tile.prerequisites().contains(p.objectiveKey())) continue;
            totals.merge(p.objectiveKey(), p.amount(), Long::sum);
            if (!EventScoring.available(tile, totals, byKey)) since = null;
            else if (since == null) since = p.createdAt();
        }
        return since;
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
     * An XP tile's progress key: the subject (a user id, or {@code t<teamId>} when the event scores by team), the tile and the progress already
     * counted that this entry builds on. Two passes that start from the same counted total (a re-read, or two bot instances at once) get the same
     * key, so only the first is written, whatever each read.
     */
    static String tileSourceKey(String subject, String objectiveKey, long builtOn) {
        return TILE_KEY_PREFIX + subject + ":" + objectiveKey + ":" + builtOn;
    }

    private boolean recordTile(Event event, Participant player, EventRules.ObjectiveSpec tile, PluginObjectiveMatcher.XpGoal goal, long gained, long already, String subject,
                               boolean byTeam) {
        long reaches = PluginObjectiveMatcher.xpProgress(goal, tile, gained);
        if (reaches <= already) return false;
        try {
            service.recordProgress(event.guildId(), event.id(), tile.key(), player.userId(), reaches - already, Source.AUTO, tileSourceKey(subject, tile.key(), already), null,
                    String.format(java.util.Locale.ROOT, "RuneSync: %,d %s gained%s", gained, goal.what(), byTeam ? " by the team" : ""));
        } catch (EventProblem e) {
            // another pass built on this total first, or the event stopped running meanwhile: nothing to add right now
            log.debug("Event {}: XP tile {} not counted for {}: {}", event.id(), tile.key(), subject, e.getMessage());
            return false;
        }
        return true;
    }

    /** The hub confirmed the player's RuneLink from public data ({@code verification} on the gains row; an older hub without it: not confirmed). */
    static boolean confirmed(DataObject row) {
        String level = row.getString("verification", "implicit");
        return level.equals("public_data_corroborated") || level.equals("jagex_signed");
    }

    private boolean record(Event event, Participant player, long add, Instant now) {
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

    /**
     * The player's alias for this bot, asked of the hub at most every {@link #ALIAS_KEPT}. A failed lookup throws {@link RuneClansHubClient.Unreachable}
     * and keeps the expired alias, and for {@link #ALIAS_RETRY} after it no lookup is tried at all, so a hub that is down or busy is asked once
     * per pass, not once per player.
     */
    private Optional<String> alias(long userId, Instant now) {
        CachedAlias cached = aliases.get(userId);
        if (cached != null && cached.until().isAfter(now)) return cached.alias();
        if (now.isBefore(aliasLookupsPausedUntil)) throw new RuneClansHubClient.Unreachable("The hub couldn't look up links a moment ago");
        Optional<String> alias;
        try {
            alias = hub.aliasOf(userId);
        } catch (RuneClansHubClient.Unreachable e) {
            aliasLookupsPausedUntil = now.plus(ALIAS_RETRY);
            throw e;
        }
        aliases.put(userId, new CachedAlias(alias, now.plus(ALIAS_KEPT)));
        if (aliases.size() > 50_000) aliases.entrySet().removeIf(e -> !e.getValue().until().isAfter(now));
        return alias;
    }
}
