package com.younglings.bot.absence;

import com.younglings.bot.absence.AbsenceRepository.Notice;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Leave of absence (T-190): a member says they're away until a date, so the dashboard's Needs attention list stops calling them inactive or
 * not capped meanwhile and says "away until …" instead. Off until the server switches it on (dashboard, Leave of absence page). The same
 * notice is set with {@code /absence}, on the member's website profile, and ended early by staff on the dashboard. Notices end on their own;
 * ended ones are pruned a week later.
 */
@BService
public class AbsenceService {
    private static final Logger log = LoggerFactory.getLogger(AbsenceService.class);
    private static final int KEEP_ENDED_DAYS = 7;

    /** What saving a notice did: the saved notice, or why it was refused. */
    public record Outcome(Notice notice, String problem) {
        public boolean ok() {
            return problem == null;
        }
    }

    private final AbsenceRepository repository;
    private final AtomicBoolean started = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "absence-prune");
        thread.setDaemon(true);
        return thread;
    });

    public AbsenceService(AbsenceRepository repository) {
        this.repository = repository;
    }

    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    public boolean enabled(long guildId) {
        return repository.enabled(guildId);
    }

    public void setEnabled(long guildId, boolean on) throws SQLException {
        repository.setEnabled(guildId, on);
    }

    /** Saves a notice after checking it; refuses while the server has the feature off. */
    public Outcome set(long guildId, long userId, LocalDate until, String reason) {
        if (!repository.enabled(guildId)) return new Outcome(null, "Leave of absence isn't switched on in this server.");
        String problem = AbsenceRules.problem(until, reason, today());
        if (problem != null) return new Outcome(null, problem);
        try {
            return new Outcome(repository.save(guildId, userId, until, AbsenceRules.cleanReason(reason)), null);
        } catch (SQLException e) {
            log.warn("Could not save an absence notice in {}", guildId, e);
            return new Outcome(null, "That didn't save. Try again in a moment.");
        }
    }

    /** Ends a member's notice now; false when they had none (or it couldn't be removed). */
    public boolean end(long guildId, long userId) {
        try {
            return repository.end(guildId, userId);
        } catch (SQLException e) {
            log.warn("Could not end an absence notice in {}", guildId, e);
            return false;
        }
    }

    public Optional<Notice> current(long guildId, long userId) {
        return repository.current(guildId, userId, today());
    }

    /** Current notices, soonest back first; empty while the feature is off (so nothing is left out of Needs attention then). */
    public List<Notice> active(long guildId) {
        return repository.enabled(guildId) ? repository.active(guildId, today()) : List.of();
    }

    /** Current notices by Discord user id. */
    public Map<Long, Notice> activeByUser(long guildId) {
        return active(guildId).stream().collect(Collectors.toMap(Notice::userId, Function.identity(), (a, b) -> a));
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        if (!started.compareAndSet(false, true)) return;
        executor.scheduleWithFixedDelay(() -> {
            int gone = repository.pruneEndedBefore(today().minusDays(KEEP_ENDED_DAYS));
            if (gone > 0) log.info("Pruned {} ended absence notices", gone);
        }, 10, 24 * 60, TimeUnit.MINUTES);
    }
}
