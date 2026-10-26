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

    public PluginXpTracker(EventRepository repository, EventService service, RuneClansHubClient hub) {
        this.repository = repository;
        this.service = service;
        this.hub = hub;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!hub.enabled()) return;
        CompetitionTemplate.offerPluginSource(true);
        executor.scheduleWithFixedDelay(this::sweep, 1, PERIOD.toMinutes(), TimeUnit.MINUTES);
    }

    /** For tests. */
    PluginXpTracker withClock(Clock clock) {
        this.clock = clock;
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
