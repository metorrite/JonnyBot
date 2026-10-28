package com.younglings.bot.plugin;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.plugin.PluginPointsRepository.XpDay;
import com.younglings.bot.plugin.PluginPointsRules.XpRule;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsRules.Category;
import com.younglings.bot.points.PointsService;
import com.younglings.bot.runeclans.RuneClansHubClient;
import com.younglings.bot.util.BotScheduler;
import com.younglings.bot.discord.Shards;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pays clan points for the XP members gain (T-542, runeclans-hub docs/XP-LEDGER.md section 6.3): a clan's XP rule ({@link XpRule}, off until staff
 * switch it on on the dashboard's Clan plugin page) pays N points for every M XP a member gained in one UTC day, held to a daily cap, once the day is
 * over. Per-event XP points are the competitions' own ({@code xpPerPoint}) and have nothing to do with this.
 * <ul>
 *   <li><b>Who:</b> as RuneSync XP comes in (a {@link PluginEventSink}: the bot's own ingest or the hub's feed), the member is noted for that UTC day
 *       in each server they share with the bot whose rule is on ({@code plugin_xp_points_day}).</li>
 *   <li><b>How much:</b> every 15 minutes, each day that ended at least {@link #GRACE} ago is settled: one {@code POST /v1/guilds/{id}/xp/gains} per
 *       server and day (up to 500 members a call) asks the hub's ledger what each gained from midnight to midnight UTC, timed XP only (catch-up and
 *       suspect XP never pay), and the rule turns that into points: one award per member per day, "RuneSync: 1.2M XP gained".</li>
 *   <li><b>Never twice:</b> each award's source key is {@code plugin:XP:<user>:<day>}, which the points ledger pays once per server, and a settled day
 *       is never read again, so a re-run of a day pays nothing. XP that reaches the hub after its day was settled isn't paid.</li>
 *   <li><b>Off:</b> nothing is paid unless the rule is on when the day is settled and the server uses points. A day the hub can't answer is tried
 *       again on the next pass, for up to {@link #RETRY_DAYS} days.</li>
 * </ul>
 * Runs only with {@code PLUGIN_XP_POINTS_ENABLED} and the hub client ({@code RUNECLANS_HUB_ENABLED}) on; otherwise the rule isn't even offered.
 */
@BService
public class PluginXpPoints implements PluginEventSink {
    private static final Logger log = LoggerFactory.getLogger(PluginXpPoints.class);
    private static final Duration PERIOD = Duration.ofMinutes(15);
    /** How long after midnight UTC a day is settled, so its last minutes of XP have reached the hub. */
    static final Duration GRACE = Duration.ofMinutes(15);
    /** How many days back a day that couldn't be settled (hub down, bot off) is still tried. */
    static final int RETRY_DAYS = 7;
    /** How long the bookkeeping of a day is kept (the points ledger keeps what was paid for good). */
    static final int KEPT_DAYS = 35;
    /** How long a member's day stays noted before their servers are checked again, so a rule switched on mid-day picks them up. */
    static final Duration NOTED_KEPT = Duration.ofHours(1);
    /** How long a Discord account's alias (or the lack of one) is kept before the hub is asked again. */
    static final Duration ALIAS_KEPT = Duration.ofMinutes(15);
    /** The hub takes at most this many players per gains call. */
    static final int MAX_PLAYERS_PER_CALL = 500;

    /** {@code alias} empty: the hub said there is none. Only definite answers are kept. */
    private record CachedAlias(Optional<String> alias, Instant until) {}

    private final PluginPointsRepository repository;
    private final PointsRepository points;
    private final PointsService pointsService;
    private final RuneClansHubClient hub;
    private final boolean switchedOn;
    private final ScheduledExecutorService executor = BotScheduler.lane("plugin-xp-points");
    private final Map<String, Instant> noted = new ConcurrentHashMap<>();
    private final Map<Long, CachedAlias> aliases = new ConcurrentHashMap<>();
    private volatile JDA jda;
    private Clock clock = Clock.systemUTC();

    public PluginXpPoints(PluginPointsRepository repository, PointsRepository points, PointsService pointsService, RuneClansHubClient hub, BotConfig config) {
        this.repository = repository;
        this.points = points;
        this.pointsService = pointsService;
        this.hub = hub;
        this.switchedOn = config.getPluginXpPointsEnabled();
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
        if (!offered()) return;
        executor.scheduleWithFixedDelay(this::sweep, 2, PERIOD.toMinutes(), TimeUnit.MINUTES);
    }

    /** For tests. */
    PluginXpPoints withClock(Clock clock) {
        this.clock = clock;
        return this;
    }

    /** Whether clans can use the XP rule here at all: the bot's switch and the hub client are both on. */
    public boolean offered() {
        return switchedOn && hub.enabled();
    }

    /** The source key of a member's award for one day; the points ledger pays a key once per server. */
    static String key(long userId, LocalDate day) {
        return PluginPointsSink.keyPrefix(PluginEventType.XP) + userId + ":" + day;
    }

    @Override
    public void accepted(long userId, String rsn, List<PluginPayload.Event> events) {
        JDA current = jda;
        if (current == null || !offered()) return;
        try {
            Instant now = clock.instant();
            Set<LocalDate> days = xpDays(events, now);
            if (days.isEmpty()) return;
            User user = Shards.user(current, userId);
            if (user == null) return;
            List<Long> guilds = new ArrayList<>();
            for (Guild guild : Shards.mutualGuilds(user)) guilds.add(guild.getIdLong());
            note(userId, guilds, days, now);
        } catch (RuntimeException e) {
            // never fails the upload
            log.warn("Couldn't note RuneSync XP for points", e);
        }
    }

    /** The UTC days these reports' XP fell on that can still be settled: today and yesterday (older XP has had its day). */
    static Set<LocalDate> xpDays(List<PluginPayload.Event> events, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Set<LocalDate> days = new TreeSet<>();
        for (PluginPayload.Event e : events) {
            if (e.type() != PluginEventType.XP || e.at() == null) continue;
            LocalDate day = LocalDate.ofInstant(e.at(), ZoneOffset.UTC);
            if (!day.isAfter(today) && !day.isBefore(today.minusDays(1))) days.add(day);
        }
        return days;
    }

    /** Notes the member's days in each of these servers whose XP rule is on, checking a member's servers at most every {@link #NOTED_KEPT}. */
    void note(long userId, List<Long> guildIds, Set<LocalDate> days, Instant now) {
        for (LocalDate day : days) {
            String key = userId + ":" + day;
            Instant until = noted.get(key);
            if (until != null && until.isAfter(now)) continue;
            noted.put(key, now.plus(NOTED_KEPT));
            for (long guildId : guildIds) {
                try {
                    if (repository.xpRule(guildId).on()) repository.noteXpDay(guildId, userId, day);
                } catch (RuntimeException e) {
                    // one server's trouble never stops another's
                    log.warn("Couldn't note an XP day in server {}", guildId, e);
                }
            }
        }
        if (noted.size() > 50_000) noted.values().removeIf(u -> !u.isAfter(now));
    }

    /** One pass: settles every day that is over, oldest first, and forgets old bookkeeping. Never throws. */
    public void sweep() {
        try {
            Instant now = clock.instant();
            LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
            // a day is settled once it ended at least GRACE ago
            LocalDate before = LocalDate.ofInstant(now.minus(GRACE), ZoneOffset.UTC);
            for (XpDay d : repository.openXpDays(today.minusDays(RETRY_DAYS), before)) {
                try {
                    settle(d.guildId(), d.day(), now);
                } catch (RuntimeException e) {
                    log.warn("Couldn't settle XP points in server {} for {}", d.guildId(), d.day(), e);
                }
            }
            repository.forgetXpDays(today.minusDays(KEPT_DAYS));
        } catch (RuntimeException e) {
            log.warn("The XP points pass failed", e);
        }
    }

    /**
     * Settles one server's day: asks the hub what each noted member gained from midnight to midnight UTC and pays the rule's points. A member the
     * hub doesn't count here (no RuneSync XP shared with the bot in this server) is settled with nothing. Returns how many members were paid.
     */
    int settle(long guildId, LocalDate day, Instant now) {
        List<Long> members = repository.openXpMembers(guildId, day);
        if (members.isEmpty()) return 0;
        XpRule rule = repository.xpRule(guildId);
        if (!rule.on() || !points.settings(guildId).enabled()) {
            // no award unless the rule is on (and the server uses points) when the day is settled
            for (long user : members) repository.settleXpDay(guildId, user, day, null, 0, now);
            return 0;
        }
        Map<String, Long> byAlias = new LinkedHashMap<>();
        for (long user : members) {
            Optional<String> alias = alias(user, now);
            if (alias == null) continue; // the hub couldn't be asked: this member waits for the next pass
            if (alias.isPresent()) byAlias.put(alias.get(), user);
            else repository.settleXpDay(guildId, user, day, null, 0, now); // no identity grant to the bot: nobody can say whose XP it is
        }
        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<String> all = new ArrayList<>(byAlias.keySet());
        int paid = 0;
        for (int i = 0; i < all.size(); i += MAX_PLAYERS_PER_CALL) {
            List<String> part = all.subList(i, Math.min(all.size(), i + MAX_PLAYERS_PER_CALL));
            RuneClansHubClient.Answer answer = hub.xpGains(guildId, part, null, from, to);
            if (answer == null || !answer.ok() || answer.json() == null) {
                // the hub is down or its ledger is off: the rest of the day waits for the next pass
                if (answer != null && answer.status() != 404) log.warn("XP points in server {} for {}: the hub answered {}", guildId, day, answer.status());
                return paid;
            }
            Map<String, Long> gained = new HashMap<>();
            DataArray rows = answer.json().optArray("players").orElse(DataArray.empty());
            for (int j = 0; j < rows.length(); j++) {
                DataObject row = rows.getObject(j);
                gained.put(row.getString("alias", ""), row.getLong("xp", 0));
            }
            for (String alias : part) {
                long user = byAlias.get(alias);
                Long xp = gained.get(alias);
                int amount = xp == null ? 0 : PluginPointsRules.xpPoints(rule, xp);
                if (amount > 0 && !pay(guildId, user, day, xp, amount)) amount = 0;
                if (repository.settleXpDay(guildId, user, day, xp, amount, now) && amount > 0) paid++;
            }
        }
        return paid;
    }

    private boolean pay(long guildId, long userId, LocalDate day, long xp, int amount) {
        PointsService.Result result = pointsService.apply(guildId, userId, amount, PluginPointsRules.xpReason(xp), Category.PLUGIN, key(userId, day), null);
        if (result.outcome() == PointsService.Outcome.INVALID) log.debug("XP points in server {} not paid: {}", guildId, result.message());
        return result.outcome() == PointsService.Outcome.DONE || result.outcome() == PointsService.Outcome.DUPLICATE;
    }

    /** The member's alias for this bot (empty: they have none), asked of the hub at most every {@link #ALIAS_KEPT}; null when the hub couldn't be asked. */
    private Optional<String> alias(long userId, Instant now) {
        CachedAlias cached = aliases.get(userId);
        if (cached != null && cached.until().isAfter(now)) return cached.alias();
        Optional<String> alias = hub.aliasIfAnswered(userId);
        if (alias == null) return null;
        aliases.put(userId, new CachedAlias(alias, now.plus(ALIAS_KEPT)));
        if (aliases.size() > 50_000) aliases.entrySet().removeIf(e -> !e.getValue().until().isAfter(now));
        return alias;
    }
}
