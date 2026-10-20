package com.younglings.bot.automod;

import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The public list of known scam and phishing websites that the Phishing links rule checks messages against. It is fetched once a day in the
 * background (never while a message is being checked) and kept in memory; if the list can't be fetched, the last good copy stays in use, and
 * with no copy at all nothing is flagged (the rule fails open). The address can be changed with {@code PHISHING_LIST_URL}; set it empty to switch fetching off.
 * The default is the community list at phish.sinking.yachts, a JSON array of domain names, which asks for an identifying header.
 */
@BService
public class PhishingList {
    private static final Logger log = LoggerFactory.getLogger(PhishingList.class);
    static final String DEFAULT_URL = "https://phish.sinking.yachts/v2/all";
    static final String IDENTITY = "JonnyBot Discord bot (https://jonnybot.rsyounglings.com)";
    static final int MAX_BYTES = 8_000_000;
    static final int MAX_DOMAINS = 300_000;
    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

    private volatile Set<String> domains = Set.of();
    private volatile Instant loadedAt;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor((ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "phishing-list");
        thread.setDaemon(true);
        return thread;
    });

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        String url = url();
        if (url.isEmpty()) {
            log.info("Phishing list fetching is switched off (PHISHING_LIST_URL is empty)");
            return;
        }
        executor.scheduleWithFixedDelay(this::refresh, 1, 24 * 60, TimeUnit.MINUTES);
    }

    private static String url() {
        String configured = System.getenv("PHISHING_LIST_URL");
        return configured == null ? DEFAULT_URL : configured.strip();
    }

    /** Whether this exact website name is on the list. */
    public boolean isListed(String host) {
        return domains.contains(host);
    }

    /** How many sites the list holds right now (0 until the first fetch works). */
    public int size() {
        return domains.size();
    }

    public Instant loadedAt() {
        return loadedAt;
    }

    void refresh() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url())).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", IDENTITY).header("X-Identity", IDENTITY).header("Accept", "application/json").GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IOException("the list is larger than " + MAX_BYTES + " bytes");
                Set<String> parsed = parse(new String(bytes, StandardCharsets.UTF_8));
                if (parsed.isEmpty()) throw new IOException("the list was empty");
                domains = parsed;
                loadedAt = Instant.now();
                log.info("Phishing list refreshed: {} sites", parsed.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // fail open: the previous copy (or nothing) stays in use
            log.warn("Phishing list could not be refreshed, keeping the previous copy ({} sites): {}", domains.size(), e.getMessage());
        }
    }

    /** The site names in a JSON array of strings, lower-cased; anything that isn't a plausible domain name is skipped. Throws if it isn't a JSON array. */
    static Set<String> parse(String json) {
        DataArray array = DataArray.fromJson(json);
        Set<String> out = new HashSet<>();
        for (int i = 0; i < array.length() && out.size() < MAX_DOMAINS; i++) {
            if (!array.isType(i, net.dv8tion.jda.api.utils.data.DataType.STRING)) continue;
            String raw = array.getString(i);
            String host = raw.strip().toLowerCase(Locale.ROOT);
            if (DOMAIN.matcher(host).matches()) out.add(host);
        }
        return out;
    }
}
