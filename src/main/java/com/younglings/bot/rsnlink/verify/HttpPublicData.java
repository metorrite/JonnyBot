package com.younglings.bot.rsnlink.verify;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * {@link PublicData} over HTTP: the two public Jagex endpoints the research tested (2026-10-20), politely. An identifying User-Agent, short
 * timeouts, a body cap, and no retries here (the verifier backs off). The answers are parsed by static methods so tests can feed them text.
 */
public final class HttpPublicData implements PublicData {
    private static final Logger log = LoggerFactory.getLogger(HttpPublicData.class);
    private static final String HISCORES = "https://secure.runescape.com/m=%s/index_lite.ws?player=%s";
    private static final String RUNEMETRICS = "https://apps.runescape.com/runemetrics/profile/profile?user=%s&activities=1";
    private static final int MAX_BODY = 64 * 1024;

    private final HttpClient http;
    private final String userAgent;
    private final BooleanSupplier runeMetricsOn;

    public HttpPublicData(String userAgent) {
        this(userAgent, () -> true);
    }

    /** @param runeMetricsOn whether RuneMetrics is still asked ({@code RUNEMETRICS}) */
    public HttpPublicData(String userAgent, BooleanSupplier runeMetricsOn) {
        this.userAgent = userAgent;
        this.runeMetricsOn = runeMetricsOn;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Override
    public Hiscores hiscores(Board board, String rsn) {
        try {
            Answer a = get(HISCORES.formatted(board.path(), encode(rsn)));
            if (a.status == 404) return new Hiscores.NotListed();
            if (a.status != 200) return new Hiscores.Failed("http " + a.status);
            return parseHiscores(a.body);
        } catch (IOException e) {
            return new Hiscores.Failed(e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Hiscores.Failed("interrupted");
        }
    }

    @Override
    public RuneMetrics runeMetrics(String rsn) {
        if (!runeMetricsOn.getAsBoolean()) return new RuneMetrics.Off();
        try {
            Answer a = get(RUNEMETRICS.formatted(encode(rsn)));
            if (a.status != 200) return new RuneMetrics.Failed("http " + a.status);
            return parseRuneMetrics(a.body);
        } catch (IOException e) {
            return new RuneMetrics.Failed(e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RuneMetrics.Failed("interrupted");
        }
    }

    /**
     * Hiscores lite: one {@code rank,level,xp} line per row, Overall first, then skills by id. A skill that isn't ranked shows {@code -1}.
     * Anything unexpected is a failure, never "unranked", because unranked would let a public value through.
     */
    static Hiscores parseHiscores(String body) {
        if (body == null) return new Hiscores.Failed("empty");
        String[] lines = body.strip().split("\\R");
        if (lines.length < Skills.COUNT + 1) return new Hiscores.Failed("short");
        long[] xp = new long[Skills.COUNT];
        for (int id = 0; id < Skills.COUNT; id++) {
            String[] parts = lines[id + 1].strip().split(",");
            if (parts.length < 3) return new Hiscores.Failed("bad line");
            try {
                long value = Long.parseLong(parts[2].strip());
                xp[id] = value < 0 ? Skills.UNRANKED : value;
            } catch (NumberFormatException e) {
                return new Hiscores.Failed("bad number");
            }
        }
        return new Hiscores.Found(xp);
    }

    /**
     * The RuneMetrics profile. {@code skillvalues[].xp} is in tenths. An {@code error} means nothing is shown to anyone: private
     * ({@code PROFILE_PRIVATE}) or not there ({@code NO_PROFILE}, {@code NOT_A_MEMBER} and the like).
     */
    static RuneMetrics parseRuneMetrics(String body) {
        DataObject json;
        try {
            json = DataObject.fromJson(body);
        } catch (RuntimeException e) {
            return new RuneMetrics.Failed("bad json");
        }
        String error = json.getString("error", null);
        if (error != null) return error.equals("PROFILE_PRIVATE") ? new RuneMetrics.Private() : new RuneMetrics.NotFound();
        DataArray values = json.optArray("skillvalues").orElse(null);
        if (values == null) return new RuneMetrics.Failed("no skills");
        long[] xp10 = new long[Skills.COUNT];
        Arrays.fill(xp10, Skills.UNRANKED);
        try {
            for (int i = 0; i < values.length(); i++) {
                DataObject v = values.getObject(i);
                int id = v.getInt("id", -1);
                if (Skills.valid(id)) xp10[id] = v.getLong("xp", Skills.UNRANKED);
            }
        } catch (RuntimeException e) {
            return new RuneMetrics.Failed("bad skills");
        }
        return new RuneMetrics.Public(xp10);
    }

    private record Answer(int status, String body) {}

    private Answer get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("User-Agent", userAgent).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes(MAX_BODY);
            if (response.statusCode() == 429) log.warn("Link verification: Jagex asked us to slow down");
            return new Answer(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private static String encode(String rsn) {
        return URLEncoder.encode(rsn, StandardCharsets.UTF_8);
    }
}
