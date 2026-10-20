package com.younglings.bot.internal;

import com.younglings.bot.config.BotConfig;
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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tells the website "this changed" so its pages refresh the moment bot data changes instead of when a cache timer runs out
 * ({@code POST <SITE_URL>/api/revalidate}, shared secret, tag names; see docs/SITE-INVALIDATION.md). Strictly best effort: it never blocks
 * or fails the write that caused it (the signal is queued, flushed on a background thread, errors are only logged), and the website's own
 * timers stay as the fallback. Signals within a second of each other are merged into one request, so a roster sync that writes 200 members
 * sends one call, not 200. Without {@code SITE_URL} or {@code INTERNAL_API_SECRET} it does nothing.
 *
 * <p>Call {@link #signal} from a write that changes what a public page shows. It is static on purpose: the repositories that change data
 * are built and tested all over the code base, and a no-op when there is no website to tell keeps them free of an extra dependency.
 */
@BService
public class SiteInvalidator {
    private static final Logger log = LoggerFactory.getLogger(SiteInvalidator.class);
    static final long DELAY_MILLIS = 1000;
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** What changed, and the website cache tags that read it (names in docs/SITE-INVALIDATION.md and the site's lib/cacheTags.ts). */
    public enum Area {
        /** Clan members, their stats and rankings. */
        ROSTER("site:members", "site:overview", "site:member", "site:leaderboard", "site:records", "hub:clans", "hub:leaderboards"),
        /** Events and their signups/scores. */
        EVENTS("site:events", "hub:events", "hub:clans"),
        POLLS("site:polls"),
        SIGNUPS("site:signups"),
        NEWS("site:news"),
        /** The site's own options (what the clan site shows). */
        OPTIONS("site:options"),
        /** What the clans hub lists: a clan's profile, verification, shared events. */
        HUB("hub:clans", "hub:events", "hub:leaderboards");

        private final List<String> tags;

        Area(String... tags) {
            this.tags = List.of(tags);
        }

        public List<String> tags() {
            return tags;
        }
    }

    static volatile SiteInvalidator instance;

    private final String endpoint;
    private final String secret;
    volatile long delayMillis = DELAY_MILLIS;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "site-invalidator");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile long lastWarn;

    /** BotCommands wants exactly one constructor, so the tests build this with a mocked {@link BotConfig} and set {@link #delayMillis}. */
    public SiteInvalidator(BotConfig config) {
        String siteUrl = config.getSiteUrl();
        this.endpoint = siteUrl == null ? null : siteUrl + "/api/revalidate";
        this.secret = config.getInternalApiSecret();
        if (endpoint == null || secret == null) log.info("SITE_URL or INTERNAL_API_SECRET is not set: the website is not told about changes (its timers apply).");
        instance = this;
    }

    /** Says these areas changed. Safe to call anywhere, any number of times; does nothing without a website configured. */
    public static void signal(Area... areas) {
        SiteInvalidator current = instance;
        if (current != null) current.queue(areas);
    }

    void queue(Area... areas) {
        if (endpoint == null || secret == null) return;
        for (Area area : areas) pending.addAll(area.tags());
        if (scheduled.compareAndSet(false, true)) scheduler.schedule(this::flush, delayMillis, TimeUnit.MILLISECONDS);
    }

    /** Sends everything queued so far as one call. Public for the tests; the scheduler calls it. */
    void flush() {
        scheduled.set(false);
        Set<String> tags = new TreeSet<>(pending);
        pending.removeAll(tags);
        if (tags.isEmpty()) return;
        DataArray list = DataArray.empty();
        tags.forEach(list::add);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(TIMEOUT)
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
