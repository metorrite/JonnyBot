package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.config.BotConfig;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Tells the website "this changed" so its pages refresh the moment bot data changes instead of when a cache timer runs out
 * ({@code POST <SITE_URL>/api/revalidate}, shared secret, tag names; see docs/SITE-INVALIDATION.md). Strictly best effort: it never blocks
 * or fails the write that caused it (the signal is queued, flushed on a background thread, errors are only logged), and the website's own
 * timers stay as the fallback. Signals within a second of each other are merged into one request, so a roster sync that writes 200 members
 * sends one call, not 200. Without {@code SITE_URL} or {@code INTERNAL_API_SECRET} it does nothing.
 *
 * <p>Clan sites on runeclans.com ({@code CLAN_SITES_URL}, optional, off when unset) also get the shared tags plus per-site tags
 * {@code site:<slug>:<key>} (the builder v3 data source keys, T-393) for the server a write belongs to, so a poll vote in one server refreshes
 * that server's site and no other. The per-site tags go in their own request, so a site that does not know their shape (it answers 400 to the
 * whole batch) still gets the shared ones; they are never sent to {@code SITE_URL}.
 *
 * <p>Call {@link #signal} from a write that changes what a public page shows; pass the server when the write knows it. It is static on purpose: the repositories that change data
 * are built and tested all over the code base, and a no-op when there is no website to tell keeps them free of an extra dependency.
 */
@BService
public class SiteInvalidator {
    private static final Logger log = LoggerFactory.getLogger(SiteInvalidator.class);
    static final long DELAY_MILLIS = 1000;
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /**
     * What changed, the shared website cache tags that read it, and the builder v3 data source keys of a clan site that show it (names in
     * docs/SITE-INVALIDATION.md, the site's lib/cacheTags.ts and the builder's v3 widgets). A {@code null} key list means "the whole site".
     */
    public enum Area {
        /** Clan members, their stats and rankings. */
        ROSTER(List.of("site:members", "site:overview", "site:member", "site:leaderboard", "site:records", "hub:clans", "hub:leaderboards"),
                List.of("roster", "overview", "member", "leaderboard", "records")),
        /** Events and their signups/scores. */
        EVENTS(List.of("site:events", "hub:events", "hub:clans"), List.of("calendarEvents", "discordEvents", "eventDetail", "eventStandings", "hubEvents")),
        POLLS(List.of("site:polls"), List.of("polls")),
        SIGNUPS(List.of("site:signups"), List.of("signups")),
        NEWS(List.of("site:news"), List.of("discordFeed")),
        /** The site's own options (what the clan site shows). */
        OPTIONS(List.of("site:options"), List.of()),
        /** What the clans hub lists: a clan's profile, verification, shared events; and the clan websites built on those profiles. */
        HUB(List.of("hub:clans", "hub:events", "hub:leaderboards", "hub:sites"), null);

        private final List<String> tags;
        private final List<String> siteKeys;

        Area(List<String> tags, List<String> siteKeys) {
            this.tags = tags;
            this.siteKeys = siteKeys;
        }

        public List<String> tags() {
            return tags;
        }

        /**
         * The per-site tags of this area on the clan site at {@code slug}: {@code site:<slug>:<key>} for each key, or for {@link #HUB} (the
         * profile or the site itself changed) every read of that site, {@code site:<slug>} and the builder's {@code clan-site:<slug>}.
         */
        public List<String> siteTags(String slug) {
            if (siteKeys == null) return List.of("site:" + slug, "clan-site:" + slug);
            return siteKeys.stream().map(key -> "site:" + slug + ":" + key).toList();
        }
    }

    /** Most tags the website takes in one request. */
    static final int MAX_TAGS = 30;
    /** A clan site address the website accepts in a tag (its CLAN_SLUG, at most 40 long). */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /** A server whose areas changed; the server may be looked up later, on the background thread. */
    private record GuildSignal(Supplier<OptionalLong> guild, Area[] areas) {
    }

    static volatile SiteInvalidator instance;

    private final String endpoint;
    private final String clanSitesEndpoint;
    private final String secret;
    /** A server's clan site address; the tests swap it for a fake. */
    volatile LongFunction<Optional<String>> slugOf;
    volatile long delayMillis = DELAY_MILLIS;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ScheduledExecutorService scheduler = BotScheduler.lane("site-invalidator");
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<GuildSignal> pendingGuilds = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile long lastWarn;

    /**
     * BotCommands wants exactly one constructor, so the tests build this with a mocked {@link BotConfig} and set {@link #delayMillis} and
     * {@link #slugOf}.
     */
    public SiteInvalidator(BotConfig config, ClanProfileRepository profiles) {
        String siteUrl = config.getSiteUrl();
        String clanSitesUrl = config.getClanSitesUrl();
        this.endpoint = siteUrl == null ? null : siteUrl + "/api/revalidate";
        this.clanSitesEndpoint = clanSitesUrl == null ? null : clanSitesUrl + "/api/revalidate";
        this.secret = config.getInternalApiSecret();
        this.slugOf = guildId -> profiles == null ? Optional.empty() : profiles.get(guildId).map(ClanProfile::slug);
        if ((endpoint == null && clanSitesEndpoint == null) || secret == null) {
            log.info("SITE_URL / CLAN_SITES_URL or INTERNAL_API_SECRET is not set: the website is not told about changes (its timers apply).");
        }
        instance = this;
    }

    /** Says these areas changed. Safe to call anywhere, any number of times; does nothing without a website configured. */
    public static void signal(Area... areas) {
        SiteInvalidator current = instance;
        if (current != null) current.queue(areas);
    }

    /** Says these areas of server {@code guildId} changed: the shared tags, and that server's clan site's own tags. */
    public static void signal(long guildId, Area... areas) {
        SiteInvalidator current = instance;
        if (current != null) current.queue(() -> OptionalLong.of(guildId), areas);
    }

    /**
     * Like {@link #signal(long, Area...)} for a write that knows only a row (a poll vote, a signup entry): {@code guild} finds the server and
     * runs later on the background thread, never on the write's. Empty or failing, only the shared tags go out.
     */
    public static void signalLater(Supplier<OptionalLong> guild, Area... areas) {
        SiteInvalidator current = instance;
        if (current != null) current.queue(guild, areas);
    }

    private boolean off() {
        return secret == null || (endpoint == null && clanSitesEndpoint == null);
    }

    void queue(Area... areas) {
        if (off()) return;
        for (Area area : areas) pending.addAll(area.tags());
        schedule();
    }

    void queue(Supplier<OptionalLong> guild, Area... areas) {
        if (off()) return;
        for (Area area : areas) pending.addAll(area.tags());
        if (clanSitesEndpoint != null) pendingGuilds.add(new GuildSignal(guild, areas));
        schedule();
    }

    private void schedule() {
        if (scheduled.compareAndSet(false, true)) scheduler.schedule(this::flush, delayMillis, TimeUnit.MILLISECONDS);
    }

    /** Sends everything queued so far: the shared tags to each website, then the per-site tags to the clan sites. The scheduler calls it. */
    void flush() {
        scheduled.set(false);
        Set<String> tags = new TreeSet<>(pending);
        pending.removeAll(tags);
        Set<String> siteTags = new TreeSet<>(siteTagsOf(drainGuilds()));
        if (!tags.isEmpty()) {
            if (endpoint != null) send(endpoint, tags);
            if (clanSitesEndpoint != null && !clanSitesEndpoint.equals(endpoint)) send(clanSitesEndpoint, tags);
        }
        if (!siteTags.isEmpty()) {
            List<String> list = new ArrayList<>(siteTags);
            for (int from = 0; from < list.size(); from += MAX_TAGS) send(clanSitesEndpoint, list.subList(from, Math.min(list.size(), from + MAX_TAGS)));
        }
    }

    /** The areas that changed per server, merged, so each server is one set of tags however many writes it had. */
    private Map<Long, Set<Area>> drainGuilds() {
        Map<Long, Set<Area>> byGuild = new HashMap<>();
        GuildSignal signal;
        while ((signal = pendingGuilds.poll()) != null) {
            OptionalLong guild;
            try {
                guild = signal.guild().get();
            } catch (RuntimeException e) {
                warnOnce("could not find the server of a change: " + e);
                continue;
            }
            if (guild.isPresent()) byGuild.computeIfAbsent(guild.getAsLong(), g -> EnumSet.noneOf(Area.class)).addAll(List.of(signal.areas()));
        }
        return byGuild;
    }

    /** Each server's clan site tags, for the servers that have a site address. */
    private Set<String> siteTagsOf(Map<Long, Set<Area>> byGuild) {
        Set<String> tags = new TreeSet<>();
        byGuild.forEach((guildId, areas) -> {
            Optional<String> slug;
            try {
                slug = slugOf.apply(guildId);
            } catch (RuntimeException e) {
                warnOnce("could not read a server's site address: " + e);
                return;
            }
            slug.filter(s -> s.length() <= 40 && SLUG.matcher(s).matches()).ifPresent(s -> areas.forEach(area -> tags.addAll(area.siteTags(s))));
        });
        return tags;
    }

    private void send(String to, Collection<String> tags) {
        DataArray list = DataArray.empty();
        tags.forEach(list::add);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(to)).timeout(TIMEOUT)
                    .header("Content-Type", "application/json").header("X-Internal-Secret", secret)
                    .POST(HttpRequest.BodyPublishers.ofString(DataObject.empty().put("tags", list).toString())).build();
            http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
                if (error != null) warnOnce("could not reach the website: " + error);
                else if (response.statusCode() != 200) warnOnce("the website answered " + response.statusCode());
            });
        } catch (RuntimeException e) {
            warnOnce("could not send: " + e);
        }
    }

    /** A website that is down would otherwise log on every write; one line a minute is enough. */
    private void warnOnce(String what) {
        long now = System.currentTimeMillis();
        if (now - lastWarn > 60_000) {
            lastWarn = now;
            log.warn("Site invalidation: {} (pages refresh on their timers instead)", what);
        }
    }
}
