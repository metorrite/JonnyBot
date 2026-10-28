package com.runeclans.bot.rsnlink.verify;

import com.runeclans.bot.rsnlink.LinkVerifier;
import com.runeclans.bot.rsnlink.Verification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Plugs {@link XpVerifier} into the link service's {@link LinkVerifier} hook. Every report and every check runs on one worker thread, so the
 * plugin's request never waits on Jagex and the store never sees two writers. Raises a link to
 * {@link Verification#PUBLIC_DATA_CORROBORATED} through the service's {@link Raiser}, which also settles any dispute the link is in.
 */
public final class XpLinkVerifier implements LinkVerifier, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(XpLinkVerifier.class);
    public static final String METHOD = "xp_first_seen";

    private final XpVerifier verifier;
    private final Executor worker;
    private final ScheduledExecutorService owned;
    private volatile Raiser raiser;

    /**
     * The real one, ticking every 20 seconds on {@code executor}: a one-task-at-a-time scheduler the caller owns (the bridge passes a lane of
     * the shared scheduler, T-184), shut down on close. Taken as a parameter so this module keeps to its few imports.
     */
    public static XpLinkVerifier start(VerifySettings settings, VerifyStore store, PublicData data, Clock clock, ScheduledExecutorService executor) {
        XpLinkVerifier made = new XpLinkVerifier(settings, store, data, clock, executor, executor);
        executor.scheduleWithFixedDelay(made::tick, 20, 20, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(made::sweep, 10, 60, TimeUnit.MINUTES);
        return made;
    }

    /** {@code worker} runs reports and checks in order; tests pass a direct executor. {@code owned} is shut down on close, or null. */
    public XpLinkVerifier(VerifySettings settings, VerifyStore store, PublicData data, Clock clock, Executor worker, ScheduledExecutorService owned) {
        this.verifier = new XpVerifier(settings, store, data, this::corroborated, clock);
        this.worker = worker;
        this.owned = owned;
    }

    public XpVerifier verifier() {
        return verifier;
    }

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public void attach(Raiser raiser) {
        this.raiser = raiser;
    }

    @Override
    public boolean wantsXpReports(Verification current) {
        return verifier.settings().enabled() && current == Verification.IMPLICIT;
    }

    /** Never vouches on the spot: it queues the report and answers later through the {@link Raiser}. */
    @Override
    public Optional<VerificationResult> check(LinkContext context) {
        if (!verifier.settings().enabled() || context.current() != Verification.IMPLICIT || context.report() == null) return Optional.empty();
        UUID linkId = UUID.fromString(context.linkId());
        Object kind = context.report().get("kind");
        if ("xp".equals(kind)) {
            Map<Integer, Long> xp10 = xpOf(context.report().get("xp10"));
            String state = context.report().get("state") instanceof String s ? s : null;
            XpVerifier.Report report = new XpVerifier.Report(linkId, context.rsnDisplay(), state, xp10);
            worker.execute(() -> {
                try {
                    verifier.report(report);
                    verifier.runDue();
                } catch (RuntimeException e) {
                    log.warn("Link verification report failed for link {}: {}", linkId, e.getClass().getSimpleName());
                }
            });
        } else if ("session".equals(kind)) {
            worker.execute(() -> {
                try {
                    verifier.loggedIn(linkId);
                } catch (RuntimeException e) {
                    log.warn("Link verification login note failed for link {}: {}", linkId, e.getClass().getSimpleName());
                }
            });
        }
        return Optional.empty();
    }

    @Override
    public Map<String, Object> describe(String linkId) {
        try {
            return verifier.describe(UUID.fromString(linkId));
        } catch (RuntimeException e) {
            return Map.of("state", "unknown");
        }
    }

    @Override
    public Map<String, Object> overview() {
        return verifier.overview();
    }

    /** One worker tick: every due check. */
    void tick() {
        try {
            verifier.runDue();
        } catch (RuntimeException e) {
            log.warn("Link verification tick failed: {}", e.getClass().getSimpleName());
        }
    }

    private void sweep() {
        try {
            int removed = verifier.sweep();
            if (removed > 0) log.info("Link verification sweep removed {} old rows", removed);
        } catch (RuntimeException e) {
            log.warn("Link verification sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    private void corroborated(UUID linkId, String evidence) {
        Raiser r = raiser;
        if (r == null) {
            log.warn("Link {} corroborated but no service is attached", linkId);
            return;
        }
        r.raise(linkId.toString(), new VerificationResult(Verification.PUBLIC_DATA_CORROBORATED, METHOD, evidence));
    }

    /** The {@code xp10} map as the service passes it; anything malformed is dropped (the verifier counts it as ignored anyway). */
    static Map<Integer, Long> xpOf(Object raw) {
        Map<Integer, Long> out = new HashMap<>();
        if (raw instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof Number k && e.getValue() instanceof Number v) out.put(k.intValue(), v.longValue());
                if (out.size() >= Skills.COUNT) break;
            }
        }
        return out;
    }

    @Override
    public void close() {
        if (owned != null) owned.shutdownNow();
    }
}
