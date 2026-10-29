package com.runeclans.bot.events;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.SkillWeekRepository.Settings;
import com.runeclans.bot.events.SkillWeekRepository.Week;
import com.runeclans.bot.events.SkillWeekRules.Winner;
import com.runeclans.bot.member.MemberProfileRepository;
import com.runeclans.bot.plugin.PluginVisibilityRepository;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.runescape.PlayerLink;
import com.runeclans.bot.runescape.PlayerLinkService;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;

/**
 * Skill of the week (T-541): with it switched on (off by default, on the dashboard's Levels & points > Skill of the week), each Monday the bot opens a
 * competition in the next skill of the server's rota, Monday 00:00 UTC to Sunday 23:59:59 UTC, counted from RuneSync through the hub's XP ledger
 * ({@link PluginXpTracker}, {@code xpSource = plugin}, {@code wholeSpan}), so the standings are the ledger's gains for that skill and span. Members
 * join it like any competition. Shortly after the week ends it is brought up to date one last time, its top {@value SkillWeekRules#WINNERS} kept
 * as the week's winners, and finished. Runs only while the bot talks to the hub ({@code RUNECLANS_HUB_ENABLED}).
 * <p>
 * Options (T-711), each off by default: members vote for next week's skill among the rota's ({@link SkillWeekRepository.Mode#VOTE}, on the clan
 * website's widget); the week's skill and its winners are posted in a chosen channel through the Discord job outbox (never while this bot isn't
 * live, {@link AutoPostRouter}); and members who share RuneSync XP with the bot are put in the week's competition once each, when the hub says
 * they gained XP that week ({@link #autoEnrol}).
 */
@BService
public class SkillWeekService {
    private static final Logger log = LoggerFactory.getLogger(SkillWeekService.class);
    /** Discord answers too slow for one server's auto-enrol pass before it stops and leaves the rest to the next pass (10 minutes later). */
    static final int AUTO_SLOW_LIMIT = 3;
    /** Failed joins for one member in one week before auto-enrol gives up on them for that week. */
    static final int AUTO_JOIN_TRIES = 3;
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
    private final PluginVisibilityRepository plugins;
    private final DiscordOutbox outbox;
    private final ScheduledExecutorService executor = BotScheduler.lane("skill-of-the-week");
    private Clock clock = Clock.systemUTC();
    private volatile JDA jda;
    /** Failed auto-enrol joins this week, by "guild:week:user" (gone once they join or auto-enrol gives up). */
    private final Map<String, Integer> joinFailures = new java.util.concurrent.ConcurrentHashMap<>();

    public SkillWeekService(SkillWeekRepository weeks, EventService service, EventRepository repository, PluginXpTracker tracker, RuneClansHubClient hub, PlayerLinkService links,
                            MemberProfileRepository profiles, PluginVisibilityRepository plugins, DiscordOutbox outbox) {
        this.outbox = outbox;
        this.weeks = weeks;
        this.service = service;
        this.repository = repository;
        this.tracker = tracker;
        this.hub = hub;
        this.links = links;
        this.profiles = profiles;
        this.plugins = plugins;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!hub.enabled()) return;
        jda = event.getJda();
        executor.scheduleWithFixedDelay(this::sweep, 2, 10, TimeUnit.MINUTES);
    }

    /** For tests: the Discord connection the sweep would get when the bot is ready. */
    SkillWeekService withJda(JDA jda) {
        this.jda = jda;
        return this;
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

    /** One pass: closes the weeks that are over, then opens this week where it is switched on and the bot is still in the server. Never throws. */
    public void sweep() {
        JDA current = jda;
        sweep(guildId -> current == null || Shards.guild(current, guildId) != null);
    }

    /** {@link #sweep}, opening weeks only in the servers {@code inServer} says the bot is in (a server it left keeps its setting, but nobody could join). */
    void sweep(LongPredicate inServer) {
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
                if (!inServer.test(guildId)) continue;
                try {
                    open(guildId, now);
                } catch (RuntimeException e) {
                    log.warn("Could not open skill of the week in {}", guildId, e);
                }
                try {
                    autoEnrol(guildId, now);
                } catch (RuntimeException e) {
                    log.warn("Could not auto-enrol members in skill of the week in {}", guildId, e);
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
        // the skill comes from the rota as stored when the week is claimed, not from the settings read above: a stale claim given back by
        // resume() has just moved the rota back one
        OptionalInt claimed = weeks.claim(guildId, start);
        if (claimed.isEmpty()) return OptionalLong.empty();
        int skill = claimed.getAsInt();
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
        announce(guildId, settings.options(), eventId, "open:" + start, openText(skill, start, settings.options().mode()));
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
        // a competition an organiser cancelled (cheating, say) has no winners
        boolean counts = event.isPresent() && event.get().status() != Status.CANCELLED;
        List<Winner> winners = SkillWeekRules.winners(counts ? xpByUser(event.get().id()) : Map.of(), SkillWeekRules.WINNERS);
        DataArray json = DataArray.empty();
        for (Winner x : winners) json.add(DataObject.empty().put("rank", x.rank()).put("userId", String.valueOf(x.userId())).put("xp", x.xp()));
        // the competition is finished before the week is closed: if finishing fails (the database), the week stays open and a later pass tries again
        if (event.isPresent() && !finish(event.get())) return Optional.empty();
        if (weeks.close(w.guildId(), w.start(), json.toString()) && counts) {
            // the week is closed whatever happens to its post
            try {
                Settings settings = weeks.settings(w.guildId());
                if (settings != null && settings.options().announceChannelId() != null) {
                    Names names = new Names(false, profiles.hiddenDiscordUsers(w.guildId()), profiles.hiddenRsns(w.guildId(), true));
                    List<DataObject> rows = winners.stream().map(x -> row(w.guildId(), names, x.rank(), x.userId(), x.xp())).toList();
                    announce(w.guildId(), settings.options(), event.get().id(), "winners:" + w.start(), winnersText(w.skillId(), w.start(), rows));
                }
            } catch (RuntimeException e) {
                log.warn("Skill of the week {} in {}: the winners post couldn't be made: {}", w.start(), w.guildId(), e.getMessage());
            }
        }
        return Optional.of(winners);
    }

    // ---------- announcements (T-711) ----------

    /**
     * Posts {@code text} in the server's announcement channel through the Discord outbox (a website-triggered or timed post is never sent from a
     * web request, and never retried after an unclear failure). Nothing when no channel is chosen or this bot isn't live.
     */
    private void announce(long guildId, SkillWeekRepository.Options options, long eventId, String key, String text) {
        if (options.announceChannelId() == null) return;
        if (AutoPostRouter.diverting()) {
            log.info("Skill of the week in {}: the {} post skipped, this bot is not live", guildId, key);
            return;
        }
        try {
            outbox.enqueue(EventUpdatePostJob.KIND, guildId, 0L, "skill-week:" + guildId + ":" + key,
                    EventUpdatePostJob.payload(eventId, options.announceChannelId(), "skill-week-" + key, text));
        } catch (RuntimeException e) {
            log.warn("Skill of the week in {}: the {} post couldn't be queued: {}", guildId, key, e.getMessage());
        }
    }

    /** "This week's skill is Fletching ..." for the week's opening post: no mentions, names only. */
    static String openText(int skillId, Instant start, SkillWeekRepository.Mode mode) {
        String skill = CompetitionTemplate.skillName(skillId);
        return "**Skill of the week: " + skill + "**" + (mode == SkillWeekRepository.Mode.VOTE ? " (your vote)" : "") + "\n"
                + "Monday " + day(start) + " to Sunday " + day(SkillWeekRules.weekEnd(start)) + " (UTC). Join the competition to take part: all your " + skill
                + " XP this week counts, counted from RuneSync.";
    }

    /** The closed week's winners, as the clan website names them ("A member" for those who don't show their RuneSync activity). */
    static String winnersText(int skillId, Instant start, List<DataObject> rows) {
        StringBuilder text = new StringBuilder("**Skill of the week winners: " + CompetitionTemplate.skillName(skillId) + "** (" + day(start) + " to "
                + day(SkillWeekRules.weekEnd(start)) + ")");
        if (rows.isEmpty()) return text.append("\nNobody gained any ").append(CompetitionTemplate.skillName(skillId)).append(" XP that week.").toString();
        for (DataObject r : rows) {
            text.append("\n").append(r.getInt("rank")).append(". ").append(r.getString("name"));
            if (!r.isNull("xp")) text.append(String.format(java.util.Locale.ROOT, " (%,d XP)", r.getLong("xp")));
        }
        return text.toString();
    }

    private static String day(Instant at) {
        return java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH).withZone(java.time.ZoneOffset.UTC).format(at);
    }

    // ---------- auto-enrol (T-711) ----------

    /**
     * With auto-enrol on, puts each member who gained RuneSync XP this week (today's and yesterday's gainers by the hub, whose grant shares their
     * Discord id) in this week's running competition, once each: one who leaves isn't put back. Only members the bot sees in the server. How many
     * were added. The whole week's XP counts whenever a member joins, so nothing is lost by joining later.
     */
    public int autoEnrol(long guildId, Instant now) {
        Settings settings = weeks.settings(guildId);
        if (!settings.enabled() || !settings.options().autoEnrol()) return 0;
        Instant start = SkillWeekRules.weekStart(now);
        Optional<Week> week = weeks.week(guildId, start);
        if (week.isEmpty() || week.get().eventId() == null || week.get().closedAt() != null) return 0;
        long eventId = week.get().eventId();
        Optional<Event> event = repository.event(guildId, eventId);
        if (event.isEmpty() || event.get().status() != Status.RUNNING) return 0;
        JDA current = jda;
        net.dv8tion.jda.api.entities.Guild guild = current == null ? null : Shards.guild(current, guildId);
        if (current != null && guild == null) return 0; // the bot isn't in the server (any more)
        Set<Long> already = weeks.enrolled(guildId, start);
        already.addAll(weeks.optedOut(guildId)); // members who asked not to be entered by themselves
        Set<Long> playing = new java.util.HashSet<>();
        for (EventRepository.Participant p : repository.participants(eventId)) playing.add(p.userId());
        java.time.LocalDate today = now.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        java.time.LocalDate first = start.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        String thisWeek = guildId + ":" + start + ":";
        joinFailures.keySet().removeIf(k -> k.startsWith(guildId + ":") && !k.startsWith(thisWeek)); // last week's tries
        int added = 0;
        int slow = 0;
        for (java.time.LocalDate day : today.isAfter(first) ? List.of(today.minusDays(1), today) : List.of(today)) {
            RuneClansHubClient.Answer answer = hub.xpGainers(guildId, day);
            if (answer == null || !answer.ok() || answer.json() == null) return added; // the hub is down or its ledger is off: next pass
            DataArray rows = answer.json().optArray("players").orElse(DataArray.empty());
            for (int i = 0; i < rows.length(); i++) {
                long user;
                try {
                    user = Long.parseLong(rows.getObject(i).getString("discordId", ""));
                } catch (NumberFormatException e) {
                    continue; // their grant doesn't share who they are on Discord
                }
                if (playing.contains(user) || already.contains(user)) continue;
                if (guild != null) {
                    // the shared member lookups: 1.5 s at most, one question per member at a time, "not a member" remembered a minute
                    DiscordLookups.Outcome found = DiscordLookups.member(guild, user, DiscordLookups.BUDGET).outcome();
                    if (found == DiscordLookups.Outcome.ABSENT) continue; // not a member of this server
                    if (found == DiscordLookups.Outcome.SLOW) {
                        // Discord is slow: never entered on a guess, and after a few the rest wait for the next pass
                        if (++slow >= AUTO_SLOW_LIMIT) return finish(guildId, added);
                        continue;
                    }
                }
                already.add(user);
                String tries = thisWeek + user;
                try {
                    service.join(guildId, eventId, guildId, user, null, true, null);
                    playing.add(user);
                    added++;
                } catch (EventProblem e) {
                    // refused for a reason of its own (e.g. a full event): not tried again this week
                    log.debug("Skill of the week in {}: {} not auto-enrolled: {}", guildId, user, e.getMessage());
                } catch (RuntimeException e) {
                    // the join itself failed (the database): left unmarked, so the next pass tries again, a few times
                    int failed = joinFailures.merge(tries, 1, Integer::sum);
                    if (failed == 1) log.warn("Skill of the week in {}: could not auto-enrol {}", guildId, user, e);
                    else log.warn("Skill of the week in {}: could not auto-enrol {} (try {}): {}", guildId, user, failed, e.toString());
                    if (failed < AUTO_JOIN_TRIES) continue;
                    log.warn("Skill of the week in {}: gave up auto-enrolling {} this week", guildId, user);
                }
                joinFailures.remove(tries);
                try {
                    weeks.markEnrolled(guildId, start, user);
                } catch (RuntimeException e) {
                    // the join stands (they're playing, so never joined twice); the next pass simply looks again
                    log.warn("Skill of the week in {}: could not note {} as auto-enrolled", guildId, user, e);
                }
            }
        }
        return finish(guildId, added);
    }

    private static int finish(long guildId, int added) {
        if (added > 0) log.info("Skill of the week in {}: {} member(s) auto-enrolled", guildId, added);
        return added;
    }

    /** The member's own auto-enrol choice for the clan website: whether the server enters members by themselves, and whether they opted out. */
    public DataObject autoView(long guildId, long userId) {
        Settings settings = weeks.settings(guildId);
        return DataObject.empty().put("autoEnrol", settings.enabled() && settings.options().autoEnrol()).put("optedOut", weeks.optedOut(guildId).contains(userId));
    }

    /** Keeps the member out of (or lets them back into) auto-enrol in this server, answering {@link #autoView}. */
    public DataObject setOptOut(long guildId, long userId, boolean optedOut) {
        weeks.setOptOut(guildId, userId, optedOut);
        return autoView(guildId, userId);
    }

    // ---------- the vote (T-711) ----------

    /** The vote's state for the clan website: the week voted for, the rota's skills in order with their votes, and {@code mine} when given. */
    public DataObject voteView(long guildId, Long userId) {
        Settings settings = weeks.settings(guildId);
        if (!settings.enabled() || settings.options().mode() != SkillWeekRepository.Mode.VOTE || settings.rota().isEmpty()) return DataObject.empty().put("open", false);
        Instant week = SkillWeekRules.votingFor(clock.instant());
        Map<Integer, Integer> votes = weeks.votes(guildId, week);
        DataArray options = DataArray.empty();
        for (int skill : settings.rota()) {
            options.add(DataObject.empty().put("skillId", skill).put("skill", CompetitionTemplate.skillName(skill)).put("votes", votes.getOrDefault(skill, 0)));
        }
        DataObject out = DataObject.empty().put("open", true).put("week", week.toString()).put("end", SkillWeekRules.weekEnd(week).toString()).put("options", options);
        if (userId != null) {
            OptionalInt mine = weeks.vote(guildId, week, userId);
            out.put("mine", mine.isPresent() ? mine.getAsInt() : null);
        }
        return out;
    }

    /** A member's vote for next week's skill; refused (409) unless the server votes, and (400) for a skill not on its ballot. */
    public DataObject castVote(long guildId, long userId, int skillId) {
        Settings settings = weeks.settings(guildId);
        if (!settings.enabled() || settings.options().mode() != SkillWeekRepository.Mode.VOTE) throw new EventProblem(409, "This clan doesn't vote for its skill of the week.");
        if (!settings.rota().contains(skillId)) throw new EventProblem(400, "Choose one of the skills on the list.");
        weeks.vote(guildId, SkillWeekRules.votingFor(clock.instant()), userId, skillId);
        return voteView(guildId, userId);
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
        return CompetitionTemplate.importedXp(repository.progress(eventId));
    }

    /**
     * What the dashboard and the clan website show: the switch and rota, the skill next in line, this week (skill, span, competition and its
     * standings by XP, at most {@code shown} places) and the last closed week's winners. Players are named by their linked RuneScape name only.
     */
    public DataObject view(long guildId, int shown) {
        return view(guildId, shown, Names.ALL);
    }

    /**
     * {@link #view} for anyone (the clan website). A player is named, and their XP shown, only when they chose to show their RuneSync activity
     * on clan sites ({@link PluginVisibilityRepository#showToClan}, off by default; docs/XP-LEDGER.md section 5.4 in the hub) and didn't hide
     * themselves from the rankings or hide which Discord account is theirs: everyone else is "A member" with no XP. A week whose competition
     * keeps its standings to staff, or hides them until it ends, shows none ({@code hidden}), as on its event page (T-211).
     */
    public DataObject publicView(long guildId, int shown) {
        return view(guildId, shown, new Names(false, profiles.hiddenDiscordUsers(guildId), profiles.hiddenRsns(guildId, true)));
    }

    /** Who is named: everyone (staff), or on the clan website those who chose to be, less Discord users and (lower-cased) RuneScape names hidden. */
    private record Names(boolean staff, Set<Long> hiddenUsers, Set<String> hiddenRsns) {
        static final Names ALL = new Names(true, Set.of(), Set.of());
    }

    private DataObject view(long guildId, int shown, Names names) {
        Instant now = clock.instant();
        Settings settings = weeks.settings(guildId);
        SkillWeekRepository.Options options = settings.options();
        DataObject out = DataObject.empty().put("enabled", settings.enabled()).put("available", available()).put("rota", DataArray.fromCollection(settings.rota()))
                .put("next", settings.rota().isEmpty() ? null : SkillWeekRules.skillAt(settings.rota(), settings.position()))
                .put("mode", options.mode().name().toLowerCase(java.util.Locale.ROOT)).put("vote", voteView(guildId, null));
        // the announcement channel and auto-enrol are the dashboard's (staff) business, not the clan website's
        // whether members are entered by themselves is public: a member can opt out from the clan site
        out.put("autoEnrol", options.autoEnrol());
        if (names.staff()) out.put("announceChannelId", options.announceChannelId() == null ? null : String.valueOf(options.announceChannelId()));
        Instant start = SkillWeekRules.weekStart(now);
        DataObject current = null;
        DataObject last = null;
        for (Week w : weeks.recent(guildId, 4)) {
            if (current == null && last == null && w.start().equals(start)) {
                Optional<Event> e = w.eventId() == null ? Optional.empty() : repository.event(guildId, w.eventId());
                boolean hidden = !names.staff() && !tableShown(e);
                current = week(w, e).put("hidden", hidden);
                Map<Long, Long> xp = w.eventId() == null || hidden ? Map.of() : xpByUser(w.eventId());
                DataArray standings = DataArray.empty();
                for (Winner x : SkillWeekRules.winners(xp, Math.max(1, Math.min(shown, MAX_SHOWN)))) standings.add(row(guildId, names, x.rank(), x.userId(), x.xp()));
                current.put("standings", standings).put("players", w.eventId() == null ? 0 : repository.participants(w.eventId()).size());
            } else if (last == null && w.closedAt() != null) {
                Optional<Event> e = w.eventId() == null ? Optional.empty() : repository.event(guildId, w.eventId());
                boolean hidden = !names.staff() && !tableShown(e);
                last = week(w, e).put("hidden", hidden);
                DataArray winners = DataArray.empty();
                DataArray stored = w.winners() == null || hidden ? DataArray.empty() : DataArray.fromJson(w.winners());
                for (int i = 0; i < stored.length(); i++) {
                    DataObject x = stored.getObject(i);
                    winners.add(row(guildId, names, x.getInt("rank"), Long.parseLong(x.getString("userId")), x.getLong("xp")));
                }
                last.put("winners", winners);
            }
        }
        return out.put("current", current).put("last", last);
    }

    private static DataObject week(Week w, Optional<Event> e) {
        return DataObject.empty().put("skillId", w.skillId()).put("skill", CompetitionTemplate.skillName(w.skillId())).put("start", w.start().toString())
                .put("end", SkillWeekRules.weekEnd(w.start()).toString()).put("eventId", w.eventId() == null ? null : String.valueOf(w.eventId()))
                .put("status", e.map(x -> x.status().name()).orElse(null));
    }

    /** Whether players may see a week's standings: its competition's own setting (T-211) at its stage; a week without one has none to hide. */
    private static boolean tableShown(Optional<Event> e) {
        if (e.isEmpty()) return true;
        DataObject config;
        try {
            config = DataObject.fromJson(e.get().config());
        } catch (RuntimeException x) {
            config = null;
        }
        return EventStandings.tableShown(EventStandings.of(config), e.get().status());
    }

    private DataObject row(long guildId, Names names, int rank, long userId, long xp) {
        boolean shows = names.staff() || (!names.hiddenUsers().contains(userId) && plugins.showToClan(userId));
        List<PlayerLink> mine = shows ? links.getLinksForUser(guildId, userId) : List.of();
        boolean named = !mine.isEmpty() && !names.hiddenRsns().contains(mine.get(0).rsn().toLowerCase());
        return DataObject.empty().put("rank", rank).put("name", named ? mine.get(0).rsn() : "A member").put("xp", shows ? xp : null);
    }
}
