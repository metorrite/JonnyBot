package com.younglings.bot.internal;

import com.younglings.bot.clansite.SiteRoleGuard;
import com.younglings.bot.clansite.SiteRoles;
import com.younglings.bot.discord.jobs.DiscordJob;
import com.younglings.bot.discord.jobs.DiscordOutbox;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.CockpitRules.CapWeek;
import com.younglings.bot.internal.RankAdvisorRules.Check;
import com.younglings.bot.internal.RankAdvisorRules.Facts;
import com.younglings.bot.internal.RankAdvisorRules.Ready;
import com.younglings.bot.internal.RankAdvisorRules.Rule;
import com.younglings.bot.internal.RankAdvisorRules.Tick;
import com.younglings.bot.internal.SiteRankStore.Congrats;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The rank-up advisor (T-594) on the site admin's Clan health page, reached through {@link SiteCockpitAdmin} (so through
 * {@link ClanSiteAdminApi}, and {@link BotlessAdminApi} for bot-less sites), which has already checked the site role each route needs:
 * <ul>
 *   <li>{@code GET clan-site/cockpit/ranks} (site admins): the owner's rules per rank, who meets the rules for their next rank, who staff
 *   ticked off lately, and the optional congrats post's settings. {@code canChangeRules} says whether the asker may change them.</li>
 *   <li>{@code PUT clan-site/cockpit/ranks/rules} {@code {rules: [{rank, minXp, minDays, minCaps, minPoints}], congrats?: {on, channelId,
 *   text}}} (the site's owner): replaces every rule at once; answers the page.</li>
 *   <li>{@code POST clan-site/cockpit/ranks/ticks} {@code {rsn, rank, done, name?, congrats?}} (site admins): staff ranked the member in
 *   game (or take that back). With {@code congrats} true and the post turned on, the bot's Discord job outbox posts the congrats message;
 *   answers {@code {tick, job, congratsProblem}}.</li>
 * </ul>
 * It only advises: the bot can't change an in-game rank, and never says it did. Read-only on Discord apart from the queued post.
 */
@BService
public class SiteRankAdvisor {
    private static final Logger log = LoggerFactory.getLogger(SiteRankAdvisor.class);
    static final String PATH = "ranks";
    /** Channels offered for the congrats post, at most. */
    static final int MAX_CHANNELS = 200;

    private final SiteRankStore store;
    private final SiteCockpitStore cockpit;
    private final SiteStatsRepository stats;
    private final AdminToolsStore activity;
    private final SiteRoleGuard siteRoles;
    private final DiscordOutbox outbox;
    /** Ticks per person: a burst of 10 (working down a list), then 2 a second. */
    private final RateLimiter ticks = new RateLimiter(10, 500);
    /** Whether Citadel caps can be counted (T-725): off once the bot stops asking RuneMetrics. See {@link CitadelCaps}. */
    private final BooleanSupplier capsAvailable = CitadelCaps.fromEnv();

    public SiteRankAdvisor(SiteRankStore store, SiteCockpitStore cockpit, SiteStatsRepository stats, AdminToolsStore activity, SiteRoleGuard siteRoles,
                           DiscordOutbox outbox) {
        this.store = store;
        this.cockpit = cockpit;
        this.stats = stats;
        this.activity = activity;
        this.siteRoles = siteRoles;
        this.outbox = outbox;
    }

    /** The site role a route needs: the rules are the owner's (Drake's planning answer); seeing the list and ticking is any site admin's. */
    static SiteRoles.Action actionFor(String method, String[] parts) {
        return parts.length == 4 && parts[3].equals("rules") && !method.equals("GET") ? SiteRoles.Action.OWN : SiteRoles.Action.SETTINGS;
    }

    /** {@code clan-site/cockpit/ranks...}; null for a path it doesn't serve. */
    DataObject route(SiteCaller c, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 3 || !parts[2].equals(PATH)) return null;
        if (parts.length == 3) {
            AdminRouteExtension.requireMethod(method, "GET");
            return view(c, OffsetDateTime.now(ZoneOffset.UTC));
        }
        if (parts.length != 4) return null;
        return switch (parts[3]) {
            case "rules" -> {
                AdminRouteExtension.requireMethod(method, "PUT");
                yield saveRules(c, body.get());
            }
            case "ticks" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield tick(c, body.get());
            }
            default -> null;
        };
    }

    void forget(long guildId) {
        store.forget(guildId);
    }

    // ---------- the page ----------

    /** Each roster member's facts, caps included when they can be counted ({@code caps} null for everyone when not). */
    List<Facts> facts(long guildId, OffsetDateTime now, boolean withCaps) {
        LocalDate thisWeek = RecapPeriod.citadelWeekStart(now.toLocalDate());
        Map<String, Integer> capped = Map.of();
        Map<String, OffsetDateTime> lastLog = Map.of();
        if (withCaps) {
            List<CapWeek> grid = stats.citadelGrid(guildId, RankAdvisorRules.CAP_WINDOW_WEEKS).stream()
                    .map(w -> new CapWeek(w.rsn(), w.weekStart(), w.capped())).toList();
            capped = RankAdvisorRules.capsByMember(grid, thisWeek);
            lastLog = activity.lastActivity(guildId);
        }
        List<Facts> out = new ArrayList<>();
        for (SiteRankStore.Row r : store.roster(guildId)) {
            String key = CockpitRules.keyOf(r.rsn());
            Integer caps = withCaps ? RankAdvisorRules.capsOf(key, capped, lastLog, thisWeek) : null;
            out.add(new Facts(r.rsn(), r.rank(), r.xpGained(), r.rankSince(), r.joined(), caps, r.points()));
        }
        return out;
    }

    DataObject view(SiteCaller c, OffsetDateTime now) {
        long guildId = c.guildId();
        // T-725: caps come from the Adventurer's Log via RuneMetrics; see CitadelCaps and runeclans-hub docs/RUNEMETRICS-SUNSET.md
        boolean capsOn = capsAvailable.getAsBoolean();
        Map<String, Rule> rules = store.rules(guildId);
        List<Tick> allTicks = store.ticks(guildId);
        List<Facts> members = facts(guildId, now, capsOn);
        Map<String, Facts> byKey = new HashMap<>();
        for (Facts f : members) byKey.put(f.key(), f);
        Map<String, Long> links = c.botless() ? Map.of() : cockpit.links(guildId);

        DataArray ready = DataArray.empty();
        for (Ready r : RankAdvisorRules.ready(members, rules, allTicks, now, capsOn)) {
            DataArray checks = DataArray.empty();
            for (Check k : r.checks()) checks.add(checkJson(k));
            ready.add(person(c, r.member().rsn(), links.get(r.member().key())).put("rank", r.member().rank()).put("target", r.target())
                    .put("rankSince", iso(r.member().rankSince())).put("checks", checks));
        }

        DataArray ticked = DataArray.empty();
        OffsetDateTime showFrom = now.minusDays(RankAdvisorRules.TICK_SHOW_DAYS);
        for (Tick t : allTicks) {
            if (t.at() == null || t.at().isBefore(showFrom)) continue;
            Facts f = byKey.get(t.rsnKey());
            if (f == null) continue; // left the clan since
            boolean inGame = RankAdvisorRules.indexOf(f.rank()) >= RankAdvisorRules.indexOf(t.rank());
            ticked.add(DataObject.empty().put("rsn", f.rsn()).put("rank", f.rank()).put("target", t.rank()).put("inGame", inGame)
                    .put("by", Long.toString(t.by())).put("byName", t.byName()).put("at", iso(t.at())));
        }

        DataArray ruleRows = DataArray.empty();
        for (Rule r : rules.values()) ruleRows.add(ruleJson(r));

        boolean owner = c.may(siteRoles, SiteRoles.Action.OWN);
        Congrats congrats = store.congrats(guildId);
        DataObject congratsJson = DataObject.empty().put("available", !c.botless()).put("on", congrats.on() && !c.botless())
                .put("channelId", congrats.channelId() == null ? null : Long.toString(congrats.channelId()))
                .put("channelName", channelName(c, congrats.channelId()))
                .put("text", congrats.text() == null ? RankAdvisorRules.DEFAULT_CONGRATS : congrats.text())
                .put("defaultText", RankAdvisorRules.DEFAULT_CONGRATS)
                .put("channels", owner ? channels(c.guild()) : DataArray.empty());

        OffsetDateTime counted = members.stream().map(Facts::rankSince).filter(t -> t != null).min(OffsetDateTime::compareTo).orElse(null);
        DataArray ladder = DataArray.empty();
        RankAdvisorRules.LADDER.forEach(ladder::add);
        DataArray targets = DataArray.empty();
        RankAdvisorRules.TARGETS.forEach(targets::add);
        return DataObject.empty()
                .put("ladder", ladder)
                .put("targets", targets)
                .put("limits", DataObject.empty().put("capWindowWeeks", RankAdvisorRules.CAP_WINDOW_WEEKS).put("maxXp", RankAdvisorRules.MAX_XP)
                        .put("maxDays", RankAdvisorRules.MAX_DAYS).put("maxPoints", RankAdvisorRules.MAX_POINTS)
                        .put("tickShowDays", RankAdvisorRules.TICK_SHOW_DAYS).put("maxCongrats", RankAdvisorRules.MAX_CONGRATS))
                .put("rules", ruleRows)
                .put("caps", capsJson(capsOn))
                .put("canChangeRules", owner)
                .put("botPresent", !c.botless())
                .put("rosterSize", members.size())
                .put("xpCountedSince", iso(counted))
                .put("ready", ready)
                .put("ticked", ticked)
                .put("congrats", congratsJson)
                .put("generatedAt", now.toString());
    }

    /** One part of a rule as the page shows it; {@code unavailable} true: not counted (Citadel caps once RuneMetrics is off). */
    static DataObject checkJson(Check k) {
        return DataObject.empty().put("what", k.what()).put("need", k.need()).put("have", k.have()).put("met", k.met())
                .put("unavailable", k.unavailable());
    }

    /** Whether rule caps parts are counted; when not, the page says so on the rules card and each row (T-725). */
    static DataObject capsJson(boolean available) {
        return DataObject.empty().put("available", available).put("reason", available ? null : CitadelCaps.RULES_REASON);
    }

    static DataObject ruleJson(Rule r) {
        return DataObject.empty().put("rank", r.rank()).put("minXp", r.minXp()).put("minDays", r.minDays()).put("minCaps", r.minCaps())
                .put("minPoints", r.minPoints());
    }

    private static DataObject person(SiteCaller c, String rsn, Long discordId) {
        DataObject row = DataObject.empty().put("rsn", rsn);
        if (discordId == null || c.botless()) return row.put("discordId", null).put("discordName", null);
        net.dv8tion.jda.api.entities.Member m = c.guild().getMemberById(discordId);
        return row.put("discordId", Long.toString(discordId)).put("discordName", m == null ? null : m.getEffectiveName());
    }

    private static String channelName(SiteCaller c, Long id) {
        if (id == null || c.botless()) return null;
        GuildMessageChannel ch = c.guild().getChannelById(GuildMessageChannel.class, id);
        return ch == null ? null : ch.getName();
    }

    /** Text channels the bot can post in, from Discord's cache (never a request). */
    private static DataArray channels(Guild guild) {
        DataArray out = DataArray.empty();
        if (guild == null) return out;
        int n = 0;
        for (TextChannel ch : guild.getTextChannels()) {
            if (!ch.canTalk()) continue;
            out.add(DataObject.empty().put("id", ch.getId()).put("name", ch.getName()));
            if (++n >= MAX_CHANNELS) break;
        }
        return out;
    }

    private static String iso(OffsetDateTime t) {
        return t == null ? null : t.toString();
    }

    // ---------- writes ----------

    /** The rules a save names, checked: known ranks only, each once, whole numbers in range. Problems are added in words. */
    static List<Rule> parseRules(DataObject body, List<String> problems) {
        if (!body.hasKey("rules") || !body.isType("rules", DataType.ARRAY)) {
            problems.add("Send the rules as a list.");
            return List.of();
        }
        DataArray raw = body.getArray("rules");
        List<Rule> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < raw.length(); i++) {
            if (!raw.isType(i, DataType.OBJECT)) {
                problems.add("A rule isn't readable.");
                continue;
            }
            DataObject r = raw.getObject(i);
            String rank = RankAdvisorRules.canonical(r.getString("rank", ""));
            if (rank == null || !RankAdvisorRules.TARGETS.contains(rank)) {
                problems.add("\"" + r.getString("rank", "") + "\" isn't a rank a rule can be set for.");
                continue;
            }
            if (!seen.add(rank)) {
                problems.add(rank + " has two rules.");
                continue;
            }
            int before = problems.size();
            Long xp = RankAdvisorRules.number(value(r, "minXp"), RankAdvisorRules.MAX_XP, rank + ": XP gained", problems);
            Long days = RankAdvisorRules.number(value(r, "minDays"), RankAdvisorRules.MAX_DAYS, rank + ": days in the clan", problems);
            Long caps = RankAdvisorRules.number(value(r, "minCaps"), RankAdvisorRules.CAP_WINDOW_WEEKS, rank + ": Citadel caps", problems);
            Long points = RankAdvisorRules.number(value(r, "minPoints"), RankAdvisorRules.MAX_POINTS, rank + ": clan points", problems);
            if (problems.size() > before) continue;
            out.add(new Rule(rank, xp, days == null ? null : days.intValue(), caps == null ? null : caps.intValue(), points));
        }
        return out;
    }

    private static Object value(DataObject o, String key) {
        if (!o.hasKey(key) || o.isNull(key)) return null;
        if (o.isType(key, DataType.INT)) return o.getLong(key);
        return o.get(key);
    }

    DataObject saveRules(SiteCaller c, DataObject body) {
        List<String> problems = new ArrayList<>();
        List<Rule> rules = parseRules(body, problems);
        Congrats congrats = null;
        if (body.hasKey("congrats") && !body.isNull("congrats")) {
            if (!body.isType("congrats", DataType.OBJECT)) problems.add("The congrats settings aren't readable.");
            else congrats = parseCongrats(c, body.getObject("congrats"), problems);
        }
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), problems);
        store.saveRules(c.guildId(), rules, c.userId());
        if (congrats != null) store.saveCongrats(c.guildId(), congrats, c.userId());
        return view(c, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** The congrats post's settings: turning it on needs the bot in the server and a channel it can post in. */
    static Congrats parseCongrats(SiteCaller c, DataObject o, List<String> problems) {
        boolean on = o.getBoolean("on", false);
        String text = RankAdvisorRules.cleanCongrats(o.getString("text", ""));
        Long channelId = null;
        String raw = o.isNull("channelId") ? "" : o.getString("channelId", "");
        if (!raw.isBlank()) {
            if (!raw.matches("\\d{15,20}")) problems.add("That channel isn't readable.");
            else channelId = Long.parseLong(raw);
        }
        if (on) {
            if (c.botless()) problems.add("The congrats post needs the bot in your Discord server.");
            else if (channelId == null) problems.add("Pick a channel for the congrats post.");
            else {
                GuildMessageChannel ch = c.guild().getChannelById(GuildMessageChannel.class, channelId);
                if (ch == null) problems.add("That channel isn't in your server.");
                else if (!ch.canTalk()) problems.add("The bot can't post in #" + ch.getName() + ". Give it permission to view and send messages there.");
            }
        }
        return new Congrats(on, channelId, text);
    }

    DataObject tick(SiteCaller c, DataObject body) {
        if (!ticks.tryAcquire(Long.toString(c.userId()))) throw new ApiError(429, Text.of(c.guildId(), "dashboard.you_re_doing_that_too_fast"));
        String rsn = body.getString("rsn", "");
        String rank = RankAdvisorRules.canonical(body.getString("rank", ""));
        if (rank == null || !RankAdvisorRules.TARGETS.contains(rank)) throw refused(c, "That isn't a rank this list ticks off.");
        if (!CockpitRules.plausibleName(rsn) || !cockpit.knownName(c.guildId(), rsn)) throw refused(c, "That name isn't on your clan's roster.");
        if (!body.hasKey("done") || !body.isType("done", DataType.BOOLEAN)) throw refused(c, "Say whether they were ranked up.");
        String key = CockpitRules.keyOf(rsn);
        if (!body.getBoolean("done")) {
            store.untick(c.guildId(), key, rank);
            return DataObject.empty().put("tick", null).put("job", null).put("congratsProblem", null);
        }
        String current = rosterRank(c.guildId(), key);
        if (current != null && RankAdvisorRules.indexOf(current) >= RankAdvisorRules.indexOf(rank)) {
            throw refused(c, "The clan Hiscores already show " + rsn.strip() + " at " + current + " or higher. Reload the page.");
        }
        String name = SiteCockpitAdmin.displayName(c, body.getString("name", ""));
        boolean fresh = store.tick(c.guildId(), key, rank, c.userId(), name);
        DataObject tick = DataObject.empty().put("rank", rank).put("by", Long.toString(c.userId())).put("byName", name)
                .put("at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        DataObject answer = DataObject.empty().put("tick", tick).put("job", null).put("congratsProblem", null);
        // one congrats post per tick: a repeated tick (a double click, two staff at once) doesn't post it again
        if (!fresh || !body.getBoolean("congrats", false)) return answer;
        Congrats congrats = store.congrats(c.guildId());
        if (!congrats.on() || c.botless()) return answer;
        String problem = null;
        GuildMessageChannel ch = congrats.channelId() == null ? null : c.guild().getChannelById(GuildMessageChannel.class, congrats.channelId());
        if (ch == null) problem = "The congrats channel is gone, so nothing was posted. The site's owner can pick another.";
        else if (!ch.canTalk()) problem = "The bot can't post in #" + ch.getName() + ", so nothing was posted.";
        if (problem != null) return answer.put("congratsProblem", problem);
        String shown = rosterName(c.guildId(), rsn);
        String text = RankAdvisorRules.congratsText(congrats.text(), shown, rank);
        DiscordJob job = outbox.enqueue(RankCongratsJob.KIND, c.guildId(), c.userId(), "rankup:" + c.guildId() + ":" + key + ":" + rank,
                RankCongratsJob.payload(ch.getIdLong(), text));
        log.info("Clan health: {} ticked a rank-up in server {} and queued a congrats post (job {})", c.userId(), c.guildId(), job.id());
        return answer.put("job", job.ticket());
    }

    /** The member's in-game rank on the roster, or null. */
    private String rosterRank(long guildId, String key) {
        for (SiteRankStore.Row r : store.roster(guildId)) if (CockpitRules.keyOf(r.rsn()).equals(key)) return r.rank();
        return null;
    }

    /** The name as the roster spells it (the website may send any case). */
    private String rosterName(long guildId, String rsn) {
        String key = CockpitRules.keyOf(rsn);
        for (SiteRankStore.Row r : store.roster(guildId)) if (CockpitRules.keyOf(r.rsn()).equals(key)) return r.rsn();
        return rsn.strip();
    }

    private static ApiError refused(SiteCaller c, String problem) {
        return new ApiError(400, Text.of(c.guildId(), "dashboard.the_website_can_t_be_saved"), List.of(problem));
    }
}
