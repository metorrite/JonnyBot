package com.younglings.bot.runescape.polling;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * A recurring poll: every {@code period}, ask {@code targets} who should be polled and submit them all at
 * {@code priority}, spread across {@code spreadOver} so the requests trickle in instead of arriving as one wave.
 * <p>
 * The target list is a supplier, evaluated afresh each run, so a server that registers a clan or a player who links
 * joins the next run without a restart. A tier is therefore just one of these: the clan tier, the linked tier, and
 * later a premium clan tier, each with its own priority, period and window.
 *
 * @param name         shown in logs and on the status page
 * @param maxAge       a player polled more recently than this when their turn comes is skipped (see {@link PollRequest})
 * @param initialDelay how long after registration the first run happens
 * @param spreadOver   the window the run's players are spread across; zero submits them all at once
 */
public record PollJob(String name, PollPriority priority, Duration period, Duration initialDelay, Duration spreadOver,
                      Duration maxAge, Supplier<List<String>> targets) {
    public PollJob {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("A poll job needs a name");
        if (period == null || period.isZero() || period.isNegative()) throw new IllegalArgumentException("A poll job needs a positive period");
        initialDelay = initialDelay == null ? Duration.ZERO : initialDelay;
        spreadOver = spreadOver == null ? Duration.ZERO : spreadOver;
        maxAge = maxAge == null ? Duration.ZERO : maxAge;
    }

    /** The requests one run submits: {@code rsns} in order, the i-th held back {@code i / n} of the way across {@link #spreadOver}. */
    List<PollRequest> requestsFor(List<String> rsns) {
        int count = rsns.size();
        List<PollRequest> requests = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Duration delay = count == 0 ? Duration.ZERO : spreadOver.multipliedBy(i).dividedBy(count);
            requests.add(new PollRequest(rsns.get(i), priority, maxAge, delay, name));
        }
        return requests;
    }
}
