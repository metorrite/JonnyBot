package com.younglings.bot.events;

import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.SkillWeekRepository.Settings;
import com.younglings.bot.events.SkillWeekRepository.Week;
import com.younglings.bot.events.SkillWeekRules.Winner;
import com.younglings.bot.member.MemberProfileRepository;
import com.younglings.bot.runeclans.RuneClansHubClient;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkService;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Skill of the week (T-541): with it switched on (off by default, on the dashboard's Levels & points > Skill of the week), each Monday the bot opens a
 * competition in the next skill of the server's rota, Monday 00:00 UTC to Sunday 23:59:59 UTC, counted from RuneSync through the hub's XP ledger
 * ({@link PluginXpTracker}, {@code xpSource = plugin}, {@code wholeSpan}), so the standings are the ledger's gains for that skill and span. Members
 * join it like any competition. Shortly after the week ends it is brought up to date one last time, its top {@value SkillWeekRules#WINNERS} kept
 * as the week's winners, and finished. Runs only while the bot talks to the hub ({@code RUNECLANS_HUB_ENABLED}).
 */
@BService
public class SkillWeekService {
    private static final Logger log = LoggerFactory.getLogger(SkillWeekService.class);
    /** How many places the website's standings show at most. */
    public static final int MAX_SHOWN = 25;
    /** A week claimed this long ago that still has no competition was left by a pass that died (a restart): it is given back. */
    static final java.time.Duration STALE_CLAIM = java.time.Duration.ofMinutes(10);

    private final SkillWeekRepository weeks;
    private final EventService service;
    private final EventRepository repository;
    private final PluginXpTracker tracker;
    private final RuneClansHubClient hub;
    private final PlayerLinkService links;
    private final MemberProfileRepository profiles;
    private final ScheduledExecutorService executor = BotScheduler.lane("skill-of-the-week");
    private Clock clock = Clock.systemUTC();

    public SkillWeekService(SkillWeekRepository weeks, EventService service, EventRepository repository, PluginXpTracker tracker, RuneClansHubClient hub, PlayerLinkService links,
                            MemberProfileRepository profiles) {
        this.weeks = weeks;
        this.service = service;
        this.repository = repository;
        this.tracker = tracker;
        this.hub = hub;
        this.links = links;
        this.profiles = profiles;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!hub.enabled()) return;
        executor.scheduleWithFixedDelay(this::sweep, 2, 10, TimeUnit.MINUTES);
    }

    /** For tests. */
    SkillWeekService withClock(Clock clock) {
        this.clock = clock;
        return this;
    }

    /** Whether weeks can be opened at all: the bot talks to the hub. */
    public boolean available() {
        return hub.enabled();
    }

    /** One pass: closes the weeks that are over, then opens this week where it is switched on. Never throws. */
    public void sweep() {
        Instant now = clock.instant();
        try {
            for (Week w : weeks.unclosedBefore(SkillWeekRules.weekStart(now).plusSeconds(1))) {
                if (!SkillWeekRules.closeDue(SkillWeekRules.weekEnd(w.start()), now)) continue;
                try {
                    close(w);
                } catch (RuntimeException e) {
                    log.warn("Could not close skill of the week {} in {}", w.start(), w.guildId(), e);
                }
            }
            for (long guildId : weeks.enabledGuilds()) {
                try {
                    open(guildId, now);
                } catch (RuntimeException e) {
                    log.warn("Could not open skill of the week in {}", guildId, e);
                }
            }
        } catch (RuntimeException e) {
            log.warn("The skill of the week pass failed", e);
        }
    }

    /** Opens this week's competition when the server has skill of the week on and the week isn't open yet; its event id, or empty. */
    public OptionalLong open(long guildId, Instant now) {
        Settings settings = weeks.settings(guildId);
        if (!settings.enabled() || settings.rota().isEmpty()) return OptionalLong.empty();
        Instant start = SkillWeekRules.weekStart(now);
        Optional<Week> existing = weeks.week(guildId, start);
        if (existing.isPresent() && !resume(existing.get(), now)) return OptionalLong.empty();
        int skill = SkillWeekRules.skillAt(settings.rota(), settings.position());
        if (!weeks.claim(guildId, start, skill)) return OptionalLong.empty();
        DataObject config = DataObject.empty().put("skillId", skill).put("xpPerPoint", SkillWeekRules.XP_PER_POINT).put("xpSource", CompetitionTemplate.SOURCE_PLUGIN)
                .put("wholeSpan", true);
        long eventId;
        try {
            eventId = service.createWholeSpanEvent(guildId, settings.updatedBy(), new EventService.EventInput(SkillWeekRules.eventName(skill, start),
                    "Who gains the most " + CompetitionTemplate.skillName(skill) + " XP this week, Monday to Sunday (UTC), counted from RuneSync. Join to take part: all your XP this week counts, "
                            + "whenever you join.", CompetitionTemplate.KEY, Scope.PRIVATE, ScoringMode.INDIVIDUAL, start, SkillWeekRules.weekEnd(start), 0, config));
        } catch (RuntimeException e) {
            // too many unfinished events, the database, or the like: give the week back so a later pass tries again
            weeks.release(guildId, start);
            log.info("Skill of the week in {} couldn't be opened: {}", guildId, e.getMessage());
            return OptionalLong.empty();
        }
        try {
            weeks.setEvent(guildId, start, eventId);
        } catch (RuntimeException e) {
            // the week can't point at it: cancel it and give the week back, so no stray draft counts toward the open events
            cancel(guildId, eventId);
            weeks.release(guildId, start);
            log.warn("Skill of the week {} in {} couldn't be kept: {}", eventId, guildId, e.getMessage());
            return OptionalLong.empty();
        }
        start(guildId, eventId);
        log.info("Opened skill of the week in {}: {} (event {})", guildId, CompetitionTemplate.skillName(skill), eventId);
        return OptionalLong.of(eventId);
    }

    /**
     * This week's competition was made but didn't start (it stays a draft otherwise, and counts nothing): try again on each pass. A week claimed
     * by a pass that died before its competition was kept is given back, and any draft that pass left cancelled, so it doesn't count toward the
     * server's unfinished events: true then, and the week is opened again.
     */
    private boolean resume(Week w, Instant now) {
        if (w.closedAt() != null) return false;
        if (w.eventId() == null) {
            if (!weeks.releaseStale(w.guildId(), w.start(), now.minus(STALE_CLAIM))) return false;
            String name = SkillWeekRules.eventName(w.skillId(), w.start());
            for (Event e : repository.events(w.guildId(), List.of(Status.DRAFT), 50)) {
                if (e.template().equals(CompetitionTemplate.KEY) && e.name().equals(name) && w.start().equals(e.startsAt())) cancel(w.guildId(), e.id());
            }
            log.info("Skill of the week {} in {} was left without its competition: opening it again", w.start(), w.guildId());
            return true;
        }
        Optional<Event> event = repository.event(w.guildId(), w.eventId());
        if (event.isPresent() && (event.get().status() == Status.DRAFT || event.get().status() == Status.SIGNUPS)) start(w.guildId(), w.eventId());
        return false;
    }

    private void start(long guildId, long eventId) {
        try {
            service.moveStage(guildId, eventId, Status.RUNNING);
        } catch (RuntimeException e) {
            log.warn("Skill of the week {} in {} was made but couldn't start yet: {}", eventId, guildId, e.getMessage());
        }
    }

    private void cancel(long guildId, long eventId) {
        try {
            service.moveStage(guildId, eventId, Status.CANCELLED);
        } catch (RuntimeException e) {
            log.warn("Skill of the week {} in {} couldn't be cancelled: {}", eventId, guildId, e.getMessage());
        }
    }

    /**
     * Brings a finished week up to date from the ledger one last time, keeps its winners and finishes its competition. Empty, and nothing kept,
     * when the hub couldn't be asked for everyone: a later pass tries again, until {@link SkillWeekRules#GIVE_UP_AFTER} past the end, when it
     * closes with what it has. Empty too when its competition couldn't be finished yet (the database): the week stays open for a later pass.
     */
    public Optional<List<Winner>> close(Week w) {
        Optional<Event> event = w.eventId() == null ? Optional.empty() : repository.event(w.guildId(), w.eventId());
        if (event.isPresent() && event.get().status() == Status.RUNNING && !tracker.pass(event.get()).complete()) {
            Instant end = SkillWeekRules.weekEnd(w.start());
            if (!SkillWeekRules.giveUp(end, clock.instant())) {
                log.info("Skill of the week {} in {}: the hub couldn't be asked for everyone, closing it later", w.start(), w.guildId());
                return Optional.empty();
            }
            log.warn("Skill of the week {} in {}: the hub still couldn't be asked for everyone, closing it with what it has", w.start(), w.guildId());
        }
        List<Winner> winners = SkillWeekRules.winners(event.isPresent() ? xpByUser(event.get().id()) : Map.of(), SkillWeekRules.WINNERS);
        DataArray json = DataArray.empty();
        for (Winner x : winners) json.add(DataObject.empty().put("rank", x.rank()).put("userId", String.valueOf(x.userId())).put("xp", x.xp()));
        // the competition is finished before the week is closed: if finishing fails (the database), the week stays open and a later pass tries again
        if (event.isPresent() && !finish(event.get())) return Optional.empty();
        weeks.close(w.guildId(), w.start(), json.toString());
        return Optional.of(winners);
    }

    /** False when it couldn't be finished for now (not a refused step, which never changes on its own). */
    private boolean finish(Event event) {
        try {
            // one that never started counted nothing: cancelled, so it doesn't stay among the server's unfinished events
            if (event.status() == Status.DRAFT || event.status() == Status.SIGNUPS) service.moveStage(event.guildId(), event.id(), Status.CANCELLED);
            if (event.status() == Status.RUNNING) service.moveStage(event.guildId(), event.id(), Status.SCORING);
            if (event.status() == Status.RUNNING || event.status() == Status.SCORING) service.moveStage(event.guildId(), event.id(), Status.DONE);
        } catch (EventProblem e) {
            log.info("Skill of the week {} couldn't be finished: {}", event.id(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Skill of the week {} couldn't be finished yet: {}", event.id(), e.getMessage());
            return false;
        }
        return true;
    }

    /** Each player's XP gained in a week's competition: RuneSync's gains on its XP objective only, so an organiser's correction never changes it. */
    public Map<Long, Long> xpByUser(long eventId) {
        Map<Long, Long> xp = new HashMap<>();
        for (EventRepository.Progress p : repository.progress(eventId)) {
            if (p.source() == EventRules.Source.IMPORT && p.objectiveKey().equals(CompetitionTemplate.OBJECTIVE_KEY)) xp.merge(p.userId(), p.amount(), Long::sum);
        }
        return xp;
    }

    /**
     * What the dashboard and the clan website show: the switch and rota, the skill next in line, this week (skill, span, competition and its
     * standings by XP, at most {@code shown} places) and the last closed week's winners. Players are named by their linked RuneScape name only.
     */
    public DataObject view(long guildId, int shown) {
        return view(guildId, shown, Names.ALL);
    }

    /**
     * {@link #view} for anyone (the clan website): a member who hid themselves from the rankings, or hid which Discord account is theirs, shows as
     * "A member", as on every other clan-site answer.
     */
    public DataObject publicView(long guildId, int shown) {
        return view(guildId, shown, new Names(profiles.hiddenDiscordUsers(guildId), profiles.hiddenRsns(guildId, true)));
    }

    /** Who is named: Discord users and (lower-cased) RuneScape names that show as "A member". */
    private record Names(Set<Long> hiddenUsers, Set<String> hiddenRsns) {
        static final Names ALL = new Names(Set.of(), Set.of());
    }

    private DataObject view(long guildId, int shown, Names names) {
        Instant now = clock.instant();
        Settings settings = weeks.settings(guildId);
        DataObject out = DataObject.empty().put("enabled", settings.enabled()).put("available", available()).put("rota", DataArray.fromCollection(settings.rota()))
                .put("next", settings.rota().isEmpty() ? null : SkillWeekRules.skillAt(settings.rota(), settings.position()));
        Instant start = SkillWeekRules.weekStart(now);
        DataObject current = null;
        DataObject last = null;
        for (Week w : weeks.recent(guildId, 4)) {
            if (current == null && last == null && w.start().equals(start)) {
                current = week(w);
                Map<Long, Long> xp = w.eventId() == null ? Map.of() : xpByUser(w.eventId());
                DataArray standings = DataArray.empty();
                for (Winner x : SkillWeekRules.winners(xp, Math.max(1, Math.min(shown, MAX_SHOWN)))) standings.add(row(guildId, names, x.rank(), x.userId(), x.xp()));
                current.put("standings", standings).put("players", w.eventId() == null ? 0 : repository.participants(w.eventId()).size());
                Optional<Event> e = w.eventId() == null ? Optional.empty() : repository.event(guildId, w.eventId());
                current.put("status", e.map(x -> x.status().name()).orElse(null));
            } else if (last == null && w.closedAt() != null) {
                last = week(w);
                DataArray winners = DataArray.empty();
                DataArray stored = w.winners() == null ? DataArray.empty() : DataArray.fromJson(w.winners());
                for (int i = 0; i < stored.length(); i++) {
                    DataObject x = stored.getObject(i);
                    winners.add(row(guildId, names, x.getInt("rank"), Long.parseLong(x.getString("userId")), x.getLong("xp")));
                }
                last.put("winners", winners);
            }
        }
        return out.put("current", current).put("last", last);
    }

    private static DataObject week(Week w) {
        return DataObject.empty().put("skillId", w.skillId()).put("skill", CompetitionTemplate.skillName(w.skillId())).put("start", w.start().toString())
                .put("end", SkillWeekRules.weekEnd(w.start()).toString()).put("eventId", w.eventId() == null ? null : String.valueOf(w.eventId()));
    }

    private DataObject row(long guildId, Names names, int rank, long userId, long xp) {
        List<PlayerLink> mine = names.hiddenUsers().contains(userId) ? List.of() : links.getLinksForUser(guildId, userId);
        boolean named = !mine.isEmpty() && !names.hiddenRsns().contains(mine.get(0).rsn().toLowerCase());
        return DataObject.empty().put("rank", rank).put("name", named ? mine.get(0).rsn() : "A member").put("xp", xp);
    }
}
