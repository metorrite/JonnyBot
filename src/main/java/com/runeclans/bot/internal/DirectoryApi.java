package com.runeclans.bot.internal;

import com.runeclans.bot.discord.Shards;
import com.sun.net.httpserver.HttpExchange;
import com.runeclans.bot.clanprofile.ClanLeaderboard;
import com.runeclans.bot.clanprofile.ClanLeaderboard.Period;
import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfile.Focus;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.clanprofile.ProfileRules;
import com.runeclans.bot.clanprofile.PublicEvents;
import com.runeclans.bot.clanprofile.PublicEvents.EventInfo;
import com.runeclans.bot.clansite.BotlessSiteRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The public directory of clans, under {@code /internal/directory/}: not tied to any one server, so it is checked for the shared secret
 * but needs no acting user. It only ever returns what a clan chose to share: clan-level facts, never a member's name or any Discord
 * identity. A profile appears in lists only while it is {@code LISTED} (public, verified, not suspended); one that is unlisted can be
 * fetched by its address but is never listed.
 * <ul>
 *   <li>{@code GET clans}: every listed clan</li>
 *   <li>{@code GET clans/{slug}}: one clan, listed or unlisted, with every {@link DirectoryRouteModule}'s section</li>
 *   <li>{@code GET clans/{slug}/events}: that clan's shared upcoming events; for a bot-less site's clan, none and {@code needsBot: true}</li>
 *   <li>{@code POST clans/{slug}/report} {@code {reason, reporter?}}: report a listing for the bot's owner to review</li>
 *   <li>{@code GET events}: shared upcoming events of every listed clan, soonest first</li>
 *   <li>{@code GET leaderboards?period=week|month}: clans that opted in, ranked by XP their members gained</li>
 *   <li>{@code guilds/{guildId}/...}: whatever {@code sites/{slug}/...} answers for that server's own clan, any method (T-505): the website
 *   asks by the guild id on the hub's site record once sites live on the hub (D-14)</li>
 * </ul>
 * Everything else is a {@link DirectoryRouteModule}: alliances ({@link DirectoryAllianceRoutes}), site switches
 * ({@link DirectorySiteSwitchRoutes}), the events hub ({@link DirectoryHubEventRoutes}), clan websites ({@link DirectoryClanSiteRoutes})
 * and recruiting posts ({@link DirectoryRecruitingSection}). A new feature adds a module; it does not edit this class.
 */
@BService
public class DirectoryApi {
    private static final Logger log = LoggerFactory.getLogger(DirectoryApi.class);
    private static final String PREFIX = "/internal/directory/";
    private static final long LIST_TTL_MILLIS = 30_000;
    private static final long LEADERBOARD_TTL_MILLIS = 10 * 60_000;
    private static final int MIN_REASON = 10;
    private static final int MAX_REASON = 500;
    private static final int MAX_REPORTS_PER_CLAN_PER_DAY = 20;
    private static final int EVENTS_PER_CLAN = 25;
    private static final int EVENTS_OVERALL = 100;
    private static final int MAX_LEADERBOARD_CLANS = 100;

    private final ClanProfileRepository repository;
    private final ClanProfileService service;
    private final SiteStatsRepository stats;
    private final List<DirectoryRouteModule> modules;
    private final BotlessSiteRepository botless;
    private final TtlCache cache = new TtlCache();

    /** The clan site's Connections (N7): whether its Discord events show on the site. */
    private final SiteDiscordService discord;

    public DirectoryApi(ClanProfileRepository repository, ClanProfileService service, SiteStatsRepository stats, List<DirectoryRouteModule> modules,
                        BotlessSiteRepository botless, SiteDiscordService discord) {
        this.botless = botless;
        this.discord = discord;
        this.repository = repository;
        this.service = service;
        this.stats = stats;
        this.modules = modules;
        log.info("Directory API ready with {} module(s): {}", modules.size(), modules.stream().map(m -> m.getClass().getSimpleName()).toList());
    }

    /** Called after the shared secret has been checked. */
    public void handle(HttpExchange exchange, JDA jda) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (!path.startsWith(PREFIX)) {
            send(exchange, 404, DataObject.empty().put("error", "Not found"));
            return;
        }
        String[] parts = path.substring(PREFIX.length()).split("/");
        if (parts[0].equals("guilds")) {
            // guilds/{guildId}/...: the same answer as sites/{slug}/... for that server's site, so the website can ask by the guild id on the
            // hub's site record once sites live there (SITES_BACKEND=hub, D-14). The site record itself (nothing after the id) comes from the hub.
            parts = byGuild(parts);
            if (parts == null) {
                send(exchange, 404, DataObject.empty().put("error", "Not found"));
                return;
            }
        }
        String head = parts[0];

        if (head.equals("clans") && parts.length == 1) {
            if (!get(exchange, method)) return;
            send(exchange, 200, cache.get("list", LIST_TTL_MILLIS, this::buildList));
        } else if (head.equals("clans") && parts.length == 2) {
            if (!get(exchange, method)) return;
            Optional<ClanProfile> profile = viewable(parts[1]);
            if (profile.isEmpty()) {
                send(exchange, 404, DataObject.empty().put("error", "No such clan"));
                return;
            }
            send(exchange, 200, publicJson(profile.get(), true));
        } else if (head.equals("clans") && parts.length == 3 && parts[2].equals("events")) {
            if (!get(exchange, method)) return;
            Optional<ClanProfile> profile = viewable(parts[1]);
            if (profile.isEmpty()) {
                send(exchange, 404, DataObject.empty().put("error", "No such clan"));
                return;
            }
            // ?for=site: the clan's own website asks, so its Connections decide (Discord events off, or the server disconnected: none)
            boolean hidden = "site".equals(queryParam(exchange, "for")) && !discord.eventsShown(profile.get().guildId());
            DataObject answer = DataObject.empty().put("events", hidden ? DataArray.empty() : eventsJson(profile.get(), jda, EVENTS_PER_CLAN))
                    .put("generatedAt", Instant.now().toString());
            // a bot-less site's clan: its Discord events can't be read, so the widget says the bot is needed rather than looking empty
            if (Shards.guild(jda, profile.get().guildId()) == null && botless.contains(profile.get().guildId())) answer.put("needsBot", true);
            send(exchange, 200, answer);
        } else if (head.equals("clans") && parts.length == 3 && parts[2].equals("report")) {
            report(exchange, method, parts[1]);
        } else if (head.equals("events") && parts.length == 1) {
            if (!get(exchange, method)) return;
            send(exchange, 200, cache.get("events", LIST_TTL_MILLIS, () -> allEvents(jda)));
        } else if (head.equals("leaderboards") && parts.length == 1) {
            if (!get(exchange, method)) return;
            String raw = queryParam(exchange, "period");
            Optional<Period> period = raw == null ? Optional.of(Period.WEEK) : Period.parse(raw);
            if (period.isEmpty()) {
                send(exchange, 400, DataObject.empty().put("error", "period must be week or month"));
                return;
            }
            send(exchange, 200, cache.get("leaderboard:" + period.get().key(), LEADERBOARD_TTL_MILLIS, () -> leaderboard(period.get())));
        } else {
            DirectoryRequest request = new DirectoryRequest(exchange, jda, method, parts, this::viewable, p -> publicJson(p, true));
            for (DirectoryRouteModule module : modules) {
                if (module.route(request)) return;
            }
            send(exchange, 404, DataObject.empty().put("error", "Not found"));
        }
    }

    private static void send(HttpExchange exchange, int status, DataObject body) throws IOException {
        InternalApiServer.sendJson(exchange, status, body);
    }

    private static boolean get(HttpExchange exchange, String method) throws IOException {
        if (method.equals("GET")) return true;
        send(exchange, 405, DataObject.empty().put("error", "Method not allowed"));
        return false;
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    /** A profile anyone may open by its address: listed, or unlisted. */
    /** {@code guilds/{guildId}/rest...} as {@code sites/{slug}/rest...} for that server's viewable profile; null when there is none. */
    String[] byGuild(String[] parts) {
        if (parts.length < 3 || !parts[1].matches("\\d{1,20}")) return null;
        long guildId;
        try {
            guildId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        String slug = repository.get(guildId).map(ClanProfile::slug).filter(s -> s != null && !s.isBlank()).filter(s -> viewable(s).isPresent()).orElse(null);
        if (slug == null) return null;
        String[] aliased = parts.clone();
        aliased[0] = "sites";
        aliased[1] = slug;
        return aliased;
    }

    private Optional<ClanProfile> viewable(String slug) {
        return repository.bySlug(slug).filter(p -> listed(p, true));
    }

    /**
     * Whether a profile already loaded may be shown (listed, or also unlisted when {@code allowUnlisted}). Uses the loaded profile instead of
     * fetching it again, so a list of clans costs one clan-name read per clan, not a profile and a clan name (T-180).
     */
    private boolean listed(ClanProfile p, boolean allowUnlisted) {
        return service.publicView(p, service.clanNameOf(p.guildId()), allowUnlisted).isPresent();
    }

    // ---------- clans ----------

    private DataObject buildList() {
        DataArray clans = DataArray.empty();
        for (ClanProfile p : repository.shared()) {
            String clan = service.clanNameOf(p.guildId());
            if (service.publicView(p, clan, false).isEmpty()) continue;
            clans.add(publicJson(p, false, clan));
        }
        return DataObject.empty().put("clans", clans).put("generatedAt", Instant.now().toString());
    }

    private DataObject publicJson(ClanProfile p, boolean full) {
        return publicJson(p, full, service.clanNameOf(p.guildId()));
    }

    /** {@code clan}: the server's clan name ({@link ClanProfileService#clanNameOf}), passed in by a caller that already read it. */
    private DataObject publicJson(ClanProfile p, boolean full, String clan) {
        DataArray focus = DataArray.empty();
        for (Focus f : Focus.values()) if (p.focus().contains(f)) focus.add(DataObject.empty().put("key", f.name()).put("label", f.label()));
        boolean listed = ProfileRules.listing(p, clan) == ProfileRules.Listing.LISTED;
        DataObject json = DataObject.empty().put("slug", p.slug()).put("name", p.displayName()).put("tagline", p.tagline()).put("logoUrl", p.logoUrl())
                .put("colour", p.colour()).put("focus", focus).put("recruiting", DataObject.empty().put("key", p.recruiting().name()).put("label", p.recruiting().label()))
                .put("clanName", listed ? p.verifiedClanName() : null).put("verified", listed).put("listed", listed)
                .put("memberCount", clan == null ? null : service.memberCount(p.guildId())).put("onLeaderboards", listed && p.leaderboardOptIn());
        if (full) {
            json.put("description", p.description()).put("requirements", p.requirements()).put("applyUrl", p.applyUrl()).put("timezone", p.timezone())
                    .put("updatedAt", p.updatedAt() == null ? null : p.updatedAt().toString());
            for (DirectoryRouteModule module : modules) module.addToClan(p, json);
        }
        return json;
    }

    // ---------- events ----------

    /** A server's Discord events as plain facts (JDA has them in memory; no request is made). */
    static List<EventInfo> eventsOf(Guild guild) {
        List<EventInfo> list = new ArrayList<>();
        for (ScheduledEvent e : guild.getScheduledEvents()) {
            list.add(new EventInfo(e.getIdLong(), e.getName(), e.getDescription(), e.getImageUrl(), locationOf(guild, e), e.getStatus().name(),
                    e.getStartTime().toInstant(), e.getEndTime() == null ? null : e.getEndTime().toInstant(), e.getInterestedUserCount(),
                    "https://discord.com/events/" + guild.getId() + "/" + e.getId()));
        }
        return list;
    }

    private static String locationOf(Guild guild, ScheduledEvent event) {
        if (event.getType() == ScheduledEvent.Type.EXTERNAL) return event.getLocation();
        try {
            var channel = guild.getGuildChannelById(Long.parseLong(event.getLocation()));
            return channel == null ? null : (event.getType() == ScheduledEvent.Type.STAGE_INSTANCE ? "Stage: " : "Voice: ") + channel.getName();
        } catch (NumberFormatException e) {
            return event.getLocation();
        }
    }

    private static DataObject eventJson(EventInfo e) {
        return DataObject.empty().put("id", Long.toString(e.id())).put("name", e.name()).put("description", e.description()).put("imageUrl", e.imageUrl())
                .put("location", e.location()).put("status", e.status()).put("startTime", e.start().toString()).put("endTime", e.end() == null ? null : e.end().toString())
                .put("interestedCount", e.interested()).put("url", e.url());
    }

    private DataArray eventsJson(ClanProfile p, JDA jda, int limit) {
        DataArray events = DataArray.empty();
        Guild guild = Shards.guild(jda, p.guildId());
        if (guild == null) return events;
        for (EventInfo e : PublicEvents.feed(eventsOf(guild), repository.sharedEvents(p.guildId()), Instant.now(), limit)) events.add(eventJson(e));
        return events;
    }

    private DataObject allEvents(JDA jda) {
        record Row(ClanProfile clan, EventInfo event) {}
        List<Row> rows = new ArrayList<>();
        for (ClanProfile p : repository.shared()) {
            if (!listed(p, false)) continue;
            Guild guild = Shards.guild(jda, p.guildId());
            if (guild == null) continue;
            for (EventInfo e : PublicEvents.feed(eventsOf(guild), repository.sharedEvents(p.guildId()), Instant.now(), EVENTS_PER_CLAN)) rows.add(new Row(p, e));
        }
        rows.sort(Comparator.comparing((Row r) -> r.event().start()).thenComparingLong(r -> r.event().id()));
        DataArray events = DataArray.empty();
        for (Row r : rows.stream().limit(EVENTS_OVERALL).toList()) {
            events.add(eventJson(r.event()).put("clan", DataObject.empty().put("slug", r.clan().slug()).put("name", r.clan().displayName())
                    .put("logoUrl", r.clan().logoUrl()).put("colour", r.clan().colour())));
        }
        return DataObject.empty().put("events", events).put("generatedAt", Instant.now().toString());
    }

    // ---------- leaderboards ----------

    private DataObject leaderboard(Period period) {
        Instant now = Instant.now();
        OffsetDateTime since = period.since(now).atOffset(java.time.ZoneOffset.UTC);
        List<ClanLeaderboard.Entry> entries = new ArrayList<>();
        java.util.Map<String, ClanProfile> bySlug = new java.util.HashMap<>();
        for (ClanProfile p : repository.shared()) {
            if (!p.leaderboardOptIn() || !listed(p, false)) continue;
            if (entries.size() >= MAX_LEADERBOARD_CLANS) break;
            bySlug.put(p.slug(), p);
            var gains = stats.xpGains(p.guildId(), since);
            long total = 0;
            for (var g : gains) total += g.xp();
            entries.add(new ClanLeaderboard.Entry(p.slug(), p.displayName(), total, gains.size()));
        }
        DataArray array = DataArray.empty();
        int rank = 1;
        for (ClanLeaderboard.Entry e : ClanLeaderboard.rank(entries)) {
            ClanProfile p = bySlug.get(e.slug());
            array.add(DataObject.empty().put("rank", rank++).put("slug", e.slug()).put("name", e.name()).put("logoUrl", p == null ? null : p.logoUrl())
                    .put("colour", p == null ? null : p.colour()).put("xpGained", e.xpGained()).put("members", e.members()).put("perMember", e.perMember()));
        }
        return DataObject.empty().put("period", period.key()).put("since", since.toInstant().toString()).put("entries", array).put("generatedAt", now.toString());
    }

    // ---------- reports ----------

    private void report(HttpExchange exchange, String method, String slug) throws IOException {
        if (!method.equals("POST")) {
            send(exchange, 405, DataObject.empty().put("error", "Method not allowed"));
            return;
        }
        Optional<ClanProfile> profile = viewable(slug);
        if (profile.isEmpty()) {
            send(exchange, 404, DataObject.empty().put("error", "No such clan"));
            return;
        }
        DataObject body;
        try (java.io.InputStream in = exchange.getRequestBody()) {
            body = DataObject.fromJson(in);
        } catch (RuntimeException e) {
            send(exchange, 400, DataObject.empty().put("error", "That report couldn't be read."));
            return;
        }
        String reason = body.getString("reason", "").strip();
        if (reason.length() < MIN_REASON || reason.length() > MAX_REASON) {
            send(exchange, 400, DataObject.empty().put("error", "Say what is wrong, in " + MIN_REASON + " to " + MAX_REASON + " characters."));
            return;
        }
        String reporter = body.isNull("reporter") ? null : body.getString("reporter", "").strip();
        if (reporter != null && reporter.length() > 100) reporter = reporter.substring(0, 100);
        boolean kept = repository.addReport(profile.get().guildId(), reason, reporter == null || reporter.isEmpty() ? null : reporter, MAX_REPORTS_PER_CLAN_PER_DAY);
        if (!kept) {
            send(exchange, 429, DataObject.empty().put("error", "This clan has been reported a lot today already. It will be looked at."));
            return;
        }
        log.info("A report was made about clan listing {}", slug);
        send(exchange, 202, DataObject.empty().put("ok", true));
    }
}
