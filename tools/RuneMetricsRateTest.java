import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds out how fast RuneMetrics will let us poll profiles from this machine's IP address.
 * <p>
 * Standalone and independent of the bot: needs only a JDK (21+), no Maven, no database, no Discord. It uses the same
 * HTTP client, user agent, URL and timeouts as the bot's own {@code RuneScapeApiClient}, so what it sees is what the bot
 * would see. Run it from the repository root:
 * <pre>
 *   java tools/RuneMetricsRateTest.java                 full test (about 20-25 minutes)
 *   java tools/RuneMetricsRateTest.java --quick         a short smoke run (a few minutes)
 *   java tools/RuneMetricsRateTest.java --help          every option
 * </pre>
 * STOP THE BOT FIRST (or run it with RUNESCAPE_AUTO_POLL_ENABLED=false): RuneMetrics most likely rate-limits per IP
 * address, so a running bot polling at the same time would use up the same budget and ruin the measurement.
 * <p>
 * What it does: takes a sample of the clan's members (default 30), then polls each one in turn at a fixed delay,
 * repeating the whole sample once per delay, from most aggressive to most conservative: 0.5s, 1s, 2s, 3s, 5s, 10s
 * between requests. Between rounds it rests, then probes a single known-good profile until RuneMetrics answers
 * normally again, which measures how long a burst of 429s locks us out for. It finishes with two "variable" rounds that
 * change the delay from one member to the next (random, and a ramp up and down). If a round hits a run of consecutive
 * 429s it stops that round early rather than keep hammering a locked-out endpoint.
 * <p>
 * Output goes to the console and, in {@code tools/ratetest_logs/}, to three files named for the start time: a
 * {@code .log} (the console output), a {@code .csv} (one row per request: time, round, player, planned delay, actual
 * gap, time to first byte, total time, HTTP status, Retry-After, outcome) and a {@code .json} summary per round.
 * The delay is slept <em>after</em> each response, exactly as the bot's polling loops do, so the real gap between request
 * starts is the delay plus the previous request's response time; both are in the CSV.
 */
public class RuneMetricsRateTest {
    private static final String PROFILE_URL = "https://apps.runescape.com/runemetrics/profile/profile?user=%s&activities=20";
    private static final String CLAN_URL = "https://secure.runescape.com/m=clan-hiscores/members_lite.ws?clanName=%s";
    private static final Pattern ERROR_FIELD = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]*)\"");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    // ---------------------------------------------------------------- options
    private String clan = "Younglings";
    private int players = 30;
    private List<String> names = List.of();
    private List<Double> delays = List.of(0.5, 1.0, 2.0, 3.0, 5.0, 10.0);
    private int cooldownSeconds = 90;
    private int variableRequests = -1; // defaults to the sample size
    private long seed = 42;
    private int abortAfterConsecutive429 = 8;
    private boolean fixedCadence;
    private boolean http1;
    private boolean skipVariable;
    private boolean assumeYes;
    private Path outDir = Path.of("tools", "ratetest_logs");

    // ---------------------------------------------------------------- state
    private HttpClient client;
    private PrintWriter log;
    private PrintWriter csv;
    private final List<RoundResult> rounds = new ArrayList<>();
    private String probeName;
    private long requestNumber;
    private long previousStartNanos;
    private volatile boolean finished;

    public static void main(String[] args) throws Exception {
        RuneMetricsRateTest test = new RuneMetricsRateTest();
        if (!test.parse(args)) return;
        test.run();
    }

    // ================================================================ arguments

    private boolean parse(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> {
                    printHelp();
                    return false;
                }
                case "--quick" -> {
                    players = 6;
                    cooldownSeconds = 10;
                    delays = List.of(0.5, 2.0, 10.0);
                    variableRequests = 8;
                }
                case "--clan" -> clan = args[++i];
                case "--players" -> players = Integer.parseInt(args[++i]);
                case "--names" -> names = Arrays.stream(args[++i].split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                case "--delays" -> delays = Arrays.stream(args[++i].split(",")).map(s -> Double.parseDouble(s.trim())).toList();
                case "--cooldown" -> cooldownSeconds = Integer.parseInt(args[++i]);
                case "--variable-requests" -> variableRequests = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--abort-after" -> abortAfterConsecutive429 = Integer.parseInt(args[++i]);
                case "--out-dir" -> outDir = Path.of(args[++i]);
                case "--fixed-cadence" -> fixedCadence = true;
                case "--http1" -> http1 = true;
                case "--no-variable" -> skipVariable = true;
                case "--yes", "-y" -> assumeYes = true;
                default -> {
                    System.err.println("Unknown option: " + arg + " (try --help)");
                    return false;
                }
            }
        }
        if (variableRequests < 0) variableRequests = players;
        return true;
    }

    private static void printHelp() {
        System.out.println("""
                RuneMetrics rate test: how fast may this IP poll profiles?
                  java tools/RuneMetricsRateTest.java [options]

                  --quick                 short smoke run: 6 players, 0.5s/2s/10s, 10s rests, 8 variable requests
                  --clan NAME             clan whose roster is sampled (default Younglings)
                  --players N             how many members to poll per round (default 30)
                  --names a,b,c           poll exactly these names instead of a roster sample
                  --delays 0.5,1,2,3,5,10 fixed delays to test, in order, in seconds
                  --cooldown S            rest between rounds before the recovery probe (default 90)
                  --variable-requests N   requests in each variable round (default: the sample size)
                  --no-variable           skip the two variable rounds
                  --abort-after N         stop a round after N 429s in a row (default 8)
                  --fixed-cadence         start requests on a fixed schedule instead of sleeping after each response
                  --http1                 force HTTP/1.1 (the bot negotiates HTTP/2 by default)
                  --seed N                shuffles the sample and the variable delays (default 42)
                  --out-dir DIR           where the .log/.csv/.json go (default tools/ratetest_logs)
                  --yes                   don't wait for a confirmation countdown""");
    }

    // ================================================================ main flow

    private void run() throws Exception {
        Files.createDirectories(outDir);
        String stamp = STAMP.format(Instant.now());
        Path logPath = outDir.resolve("ratetest_" + stamp + ".log");
        Path csvPath = outDir.resolve("ratetest_" + stamp + ".csv");
        Path jsonPath = outDir.resolve("ratetest_" + stamp + ".json");
        log = new PrintWriter(Files.newBufferedWriter(logPath), true);
        csv = new PrintWriter(Files.newBufferedWriter(csvPath), true);
        csv.println("request_no,time_utc,round,round_kind,player,planned_delay_s,actual_gap_ms,ttfb_ms,total_ms,http_status,http_version,retry_after,bytes,outcome,note");

        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(http1 ? HttpClient.Version.HTTP_1_1 : HttpClient.Version.HTTP_2)
                .build();

        say("RuneMetrics rate test " + stamp + " UTC");
        say("log: " + logPath + "\ncsv: " + csvPath + "\njson: " + jsonPath);
        say("Java " + System.getProperty("java.version") + ", HTTP " + (http1 ? "1.1 (forced)" : "2 where the server offers it") + ", bot-style pacing: "
                + (fixedCadence ? "fixed cadence (start to start)" : "sleep after each response"));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!finished && !rounds.isEmpty() && log != null) {
                try {
                    writeJson(jsonPath, stamp, false);
                } catch (IOException ignored) {
                    // best effort on Ctrl+C
                }
            }
        }));

        List<String> sample = chooseSample();
        if (sample.isEmpty()) {
            say("No players to poll. Check --clan / --names.");
            return;
        }
        say("Sample (" + sample.size() + " players): " + String.join(", ", sample));
        say("Fixed delays to test: " + delays.stream().map(RuneMetricsRateTest::fmt).toList() + " s; rest between rounds: " + cooldownSeconds + " s; "
                + "variable rounds: " + (skipVariable ? "skipped" : variableRequests + " requests each"));
        say("""

                *** STOP THE BOT BEFORE THIS RUNS (or start it with RUNESCAPE_AUTO_POLL_ENABLED=false). ***
                RuneMetrics most likely limits per IP address; a bot polling at the same time would eat the same budget.
                Starting at the most aggressive delay on purpose, so expect some 429s early on.
                """);
        if (!assumeYes) {
            for (int s = 10; s > 0; s--) {
                System.out.print("Starting in " + s + "s (Ctrl+C to cancel)...\r");
                Thread.sleep(1000);
            }
            System.out.println();
        }

        long testStart = System.nanoTime();
        int roundNo = 0;
        for (double delay : delays) {
            roundNo++;
            runRound("fixed " + fmt(delay) + "s", "fixed", roundNo, shuffled(sample, roundNo), i -> delay);
            if (roundNo < delays.size() || !skipVariable) restAndProbe();
        }
        if (!skipVariable) {
            double min = delays.stream().min(Double::compare).orElse(0.5);
            double max = delays.stream().max(Double::compare).orElse(10.0);
            List<Double> pool = new ArrayList<>(delays);

            roundNo++;
            Random random = new Random(seed);
            List<String> order = shuffled(sample, roundNo);
            double[] randomDelays = new double[variableRequests];
            for (int i = 0; i < randomDelays.length; i++) randomDelays[i] = pool.get(random.nextInt(pool.size()));
            runRound("variable: random", "variable-random", roundNo, cycle(order, variableRequests), i -> randomDelays[i]);
            restAndProbe();

            roundNo++;
            // a triangle wave from the fastest delay up to the slowest and back, so the rate eases off and then ramps up again
            int n = variableRequests;
            runRound("variable: ramp", "variable-ramp", roundNo, cycle(shuffled(sample, roundNo), n), i -> {
                double position = n <= 1 ? 0 : (double) i / (n - 1);
                double wave = position <= 0.5 ? position * 2 : (1 - position) * 2;
                return min + (max - min) * wave;
            });
        }

        say("\nFinished in " + fmtDuration(Duration.ofNanos(System.nanoTime() - testStart)) + ".");
        printSummary();
        writeJson(jsonPath, stamp, true);
        finished = true;
        say("\nSaved: " + logPath + "\n       " + csvPath + "\n       " + jsonPath);
        log.close();
        csv.close();
    }

    // ================================================================ sample

    private List<String> chooseSample() throws IOException, InterruptedException {
        if (!names.isEmpty()) return names;
        say("Reading the " + clan + " roster...");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CLAN_URL.formatted(URLEncoder.encode(clan, StandardCharsets.UTF_8))))
                .timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.ISO_8859_1));
        if (response.statusCode() / 100 != 2) {
            say("Clan roster request returned HTTP " + response.statusCode());
            return List.of();
        }
        List<String> roster = new ArrayList<>();
        List<String> lines = response.body().lines().toList();
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",");
            if (parts.length >= 1 && !parts[0].isBlank()) roster.add(parts[0].replace(' ', ' ').trim());
        }
        Collections.shuffle(roster, new Random(seed));
        return new ArrayList<>(roster.subList(0, Math.min(players, roster.size())));
    }

    private List<String> shuffled(List<String> sample, int roundNo) {
        List<String> copy = new ArrayList<>(sample);
        Collections.shuffle(copy, new Random(seed + roundNo));
        return copy;
    }

    private static List<String> cycle(List<String> order, int count) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(order.get(i % order.size()));
        return out;
    }

    // ================================================================ one round

    private interface DelayPlan {
        double delayAfter(int index);
    }

    private void runRound(String label, String kind, int roundNo, List<String> order, DelayPlan plan) throws InterruptedException {
        say("\n=== Round " + roundNo + ": " + label + " | " + order.size() + " requests | started " + CLOCK.format(Instant.now()) + " UTC ===");
        RoundResult result = new RoundResult(roundNo, label, kind);
        rounds.add(result);
        previousStartNanos = 0;
        long roundStart = System.nanoTime();
        int consecutive429 = 0;

        for (int i = 0; i < order.size(); i++) {
            String player = order.get(i);
            double plannedDelay = plan.delayAfter(i);
            Sample s = poll(roundNo, kind, player, plannedDelay);
            result.samples.add(s);

            consecutive429 = s.outcome.equals("rate_limited") ? consecutive429 + 1 : 0;
            result.longest429Run = Math.max(result.longest429Run, consecutive429);
            say("  #%-4d %s  %-12s  delay %-5s gap %-7s ttfb %-5s total %-5s  %s%s".formatted(
                    s.requestNo, CLOCK.format(s.at), trim(player, 12), fmt(plannedDelay) + "s",
                    s.gapMs < 0 ? "-" : s.gapMs + "ms", s.ttfbMs + "ms", s.totalMs + "ms",
                    s.outcome.toUpperCase(Locale.ROOT), s.status == 200 || s.status == 0 ? "" : " (HTTP " + s.status + (s.retryAfter.isEmpty() ? "" : ", Retry-After " + s.retryAfter) + ")"));

            if (consecutive429 >= abortAfterConsecutive429) {
                result.aborted = true;
                say("  >>> " + consecutive429 + " rate limits in a row: stopping this round early so we don't keep hammering a locked-out endpoint.");
                break;
            }
            if (i < order.size() - 1) {
                if (fixedCadence) {
                    long wake = s.startNanos + (long) (plannedDelay * 1_000_000_000L);
                    long remaining = wake - System.nanoTime();
                    if (remaining > 0) Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
                } else {
                    Thread.sleep((long) (plannedDelay * 1000));
                }
            }
        }
        result.durationMs = (System.nanoTime() - roundStart) / 1_000_000;
        say("  --- round " + roundNo + " result: " + result.oneLine());
    }

    private Sample poll(int roundNo, String kind, String player, double plannedDelay) {
        Sample s = new Sample();
        s.requestNo = ++requestNumber;
        s.round = roundNo;
        s.kind = kind;
        s.player = player;
        s.plannedDelayS = plannedDelay;
        s.at = Instant.now();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(PROFILE_URL.formatted(URLEncoder.encode(player, StandardCharsets.UTF_8))))
                .timeout(Duration.ofSeconds(10)).GET().build();

        long start = System.nanoTime();
        s.startNanos = start;
        s.gapMs = previousStartNanos == 0 ? -1 : (start - previousStartNanos) / 1_000_000;
        previousStartNanos = start;
        long[] firstByte = {0};
        try {
            HttpResponse<String> response = client.send(request, info -> {
                firstByte[0] = System.nanoTime();
                return HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
            });
            long end = System.nanoTime();
            s.totalMs = (end - start) / 1_000_000;
            s.ttfbMs = firstByte[0] == 0 ? s.totalMs : (firstByte[0] - start) / 1_000_000;
            s.status = response.statusCode();
            s.version = response.version().toString();
            s.retryAfter = response.headers().firstValue("Retry-After").orElse("");
            String body = response.body() == null ? "" : response.body();
            s.bytes = body.length();
            if (s.status == 429) {
                s.outcome = "rate_limited";
                s.note = "headers: " + response.headers().map().keySet();
            } else if (s.status / 100 != 2) {
                s.outcome = "http_error";
                s.note = trim(body.replace('\n', ' '), 80);
            } else {
                Matcher m = ERROR_FIELD.matcher(body);
                if (!m.find()) s.outcome = "ok";
                else {
                    s.note = m.group(1);
                    s.outcome = switch (m.group(1)) {
                        case "PROFILE_PRIVATE" -> "private";
                        case "NO_PROFILE" -> "no_profile";
                        default -> "other_error";
                    };
                }
            }
        } catch (IOException e) {
            s.totalMs = (System.nanoTime() - start) / 1_000_000;
            s.ttfbMs = s.totalMs;
            s.outcome = "network_error";
            s.note = e.getClass().getSimpleName() + ": " + trim(String.valueOf(e.getMessage()), 80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            s.outcome = "interrupted";
        }
        if (s.outcome.equals("ok") && probeName == null) probeName = player;
        csv.println(String.join(",", String.valueOf(s.requestNo), s.at.toString(), String.valueOf(roundNo), kind, q(player), fmt(plannedDelay),
                String.valueOf(s.gapMs), String.valueOf(s.ttfbMs), String.valueOf(s.totalMs), String.valueOf(s.status), s.version,
                q(s.retryAfter), String.valueOf(s.bytes), s.outcome, q(s.note)));
        return s;
    }

    // ================================================================ rest and recovery

    private void restAndProbe() throws InterruptedException {
        RoundResult last = rounds.get(rounds.size() - 1);
        say("\n  Resting " + cooldownSeconds + "s, then probing '" + probeName + "' every 15s until RuneMetrics answers normally...");
        Thread.sleep(cooldownSeconds * 1000L);
        long restedAt = System.nanoTime();
        int probes = 0;
        while (probes < 40) {
            probes++;
            Sample s = poll(last.number, "probe", probeName == null ? rounds.get(0).samples.get(0).player : probeName, 15);
            say("  probe %d: %s (total %dms)%s".formatted(probes, s.outcome.toUpperCase(Locale.ROOT), s.totalMs, s.status == 429 ? " - still limited" : ""));
            if (!s.outcome.equals("rate_limited") && !s.outcome.equals("network_error")) break;
            Thread.sleep(15_000);
        }
        last.recoveryProbes = probes;
        last.recoverySeconds = cooldownSeconds + (System.nanoTime() - restedAt) / 1_000_000_000;
        say("  Back to normal " + last.recoverySeconds + "s after the round ended (" + probes + " probe(s)).");
        previousStartNanos = 0;
    }

    // ================================================================ results

    private static final class Sample {
        long requestNo;
        int round;
        String kind;
        String player;
        double plannedDelayS;
        Instant at;
        long startNanos;
        long gapMs;
        long ttfbMs;
        long totalMs;
        int status;
        String version = "";
        String retryAfter = "";
        long bytes;
        String outcome = "unknown";
        String note = "";
    }

    private static final class RoundResult {
        final int number;
        final String label;
        final String kind;
        final List<Sample> samples = new ArrayList<>();
        long durationMs;
        int longest429Run;
        boolean aborted;
        long recoverySeconds = -1;
        int recoveryProbes;

        RoundResult(int number, String label, String kind) {
            this.number = number;
            this.label = label;
            this.kind = kind;
        }

        long count(String outcome) {
            return samples.stream().filter(s -> s.outcome.equals(outcome)).count();
        }

        long failures() {
            return samples.stream().filter(s -> List.of("rate_limited", "http_error", "network_error", "other_error").contains(s.outcome)).count();
        }

        List<Long> sortedTotals() {
            return samples.stream().map(s -> s.totalMs).sorted().toList();
        }

        long percentile(double p) {
            List<Long> t = sortedTotals();
            return t.isEmpty() ? 0 : t.get(Math.min(t.size() - 1, (int) Math.ceil(p * t.size()) - 1));
        }

        double meanGapMs() {
            return samples.stream().filter(s -> s.gapMs >= 0).mapToLong(s -> s.gapMs).average().orElse(0);
        }

        int firstRateLimitAt() {
            for (int i = 0; i < samples.size(); i++) if (samples.get(i).outcome.equals("rate_limited")) return i + 1;
            return -1;
        }

        String oneLine() {
            return "%d requests | %d ok, %d private/missing, %d RATE-LIMITED, %d other failures | first 429 at request %s, longest run %d%s | mean gap %.0fms, median %dms, p95 %dms%s"
                    .formatted(samples.size(), count("ok"), count("private") + count("no_profile"), count("rate_limited"),
                            failures() - count("rate_limited"), firstRateLimitAt() < 0 ? "-" : firstRateLimitAt(), longest429Run,
                            aborted ? " (ABORTED EARLY)" : "", meanGapMs(), percentile(0.5), percentile(0.95),
                            recoverySeconds < 0 ? "" : " | recovered after " + recoverySeconds + "s");
        }
    }

    private void printSummary() {
        say("\n================ SUMMARY ================");
        say("%-18s %5s %5s %7s %6s %9s %8s %8s %9s".formatted("round", "reqs", "ok", "private", "429s", "1st 429", "run", "p95 ms", "recovery"));
        for (RoundResult r : rounds) {
            say("%-18s %5d %5d %7d %6d %9s %8d %8d %9s%s".formatted(r.label, r.samples.size(), r.count("ok"), r.count("private") + r.count("no_profile"),
                    r.count("rate_limited"), r.firstRateLimitAt() < 0 ? "-" : "#" + r.firstRateLimitAt(), r.longest429Run, r.percentile(0.95),
                    r.recoverySeconds < 0 ? "-" : r.recoverySeconds + "s", r.aborted ? "  aborted" : ""));
        }
        say("");
        RoundResult cleanest = null;
        for (RoundResult r : rounds) {
            if (!r.kind.equals("fixed") || r.count("rate_limited") > 0 || r.aborted) continue;
            double delay = Double.parseDouble(r.label.replace("fixed ", "").replace("s", ""));
            if (cleanest == null || delay < Double.parseDouble(cleanest.label.replace("fixed ", "").replace("s", ""))) cleanest = r;
        }
        if (cleanest == null) {
            say("No fixed delay finished without a 429. Even the most conservative one was limited, or the endpoint was still locked out from an earlier round.");
        } else {
            say("Fastest fixed delay with no 429 at all: " + cleanest.label.replace("fixed ", "") + ". Read that with care: an earlier, more aggressive round may have");
            say("left the endpoint limited for a while, and 30 requests is a small sample. Use the CSV to see exactly where each 429 landed.");
        }
    }

    private void writeJson(Path path, String stamp, boolean complete) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"started\": \"").append(stamp).append("\",\n  \"complete\": ").append(complete)
                .append(",\n  \"clan\": \"").append(esc(clan)).append("\",\n  \"abortAfterConsecutive429\": ").append(abortAfterConsecutive429)
                .append(",\n  \"rounds\": [\n");
        for (int i = 0; i < rounds.size(); i++) {
            RoundResult r = rounds.get(i);
            sb.append("    {\"round\": ").append(r.number).append(", \"label\": \"").append(esc(r.label)).append("\", \"requests\": ").append(r.samples.size())
                    .append(", \"ok\": ").append(r.count("ok")).append(", \"private\": ").append(r.count("private") + r.count("no_profile"))
                    .append(", \"rateLimited\": ").append(r.count("rate_limited")).append(", \"otherFailures\": ").append(r.failures() - r.count("rate_limited"))
                    .append(", \"firstRateLimitAtRequest\": ").append(r.firstRateLimitAt()).append(", \"longestRateLimitRun\": ").append(r.longest429Run)
                    .append(", \"abortedEarly\": ").append(r.aborted).append(", \"meanGapMs\": ").append(Math.round(r.meanGapMs()))
                    .append(", \"medianTotalMs\": ").append(r.percentile(0.5)).append(", \"p95TotalMs\": ").append(r.percentile(0.95))
                    .append(", \"durationMs\": ").append(r.durationMs).append(", \"recoverySeconds\": ").append(r.recoverySeconds)
                    .append(", \"recoveryProbes\": ").append(r.recoveryProbes).append(", \"failedPlayers\": [");
            List<String> failed = r.samples.stream().filter(s -> List.of("rate_limited", "http_error", "network_error", "other_error").contains(s.outcome))
                    .map(s -> "\"" + esc(s.player) + " (" + s.outcome + ")\"").toList();
            sb.append(String.join(", ", failed)).append("]}").append(i < rounds.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n}\n");
        Files.writeString(path, sb.toString());
    }

    // ================================================================ small helpers

    private void say(String text) {
        System.out.println(text);
        if (log != null) log.println(text);
    }

    private static String fmt(double seconds) {
        return seconds == Math.rint(seconds) ? String.valueOf((long) seconds) : String.valueOf(seconds);
    }

    private static String fmtDuration(Duration d) {
        return "%dm %02ds".formatted(d.toMinutes(), d.toSecondsPart());
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
